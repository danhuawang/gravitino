/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.parser;

import com.datastrato.gravitino.search.antlr.SearchExpressionBaseVisitor;
import com.datastrato.gravitino.search.antlr.SearchExpressionLexer;
import com.datastrato.gravitino.search.antlr.SearchExpressionParser;
import com.datastrato.gravitino.search.antlr.SearchExpressionParser.LiteralContext;
import com.google.common.collect.Lists;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.IntStream;
import org.antlr.v4.runtime.LexerNoViableAltException;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.Interval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConditionBuilderVisitor extends SearchExpressionBaseVisitor<Condition> {

  private static final Logger LOG = LoggerFactory.getLogger(ConditionBuilderVisitor.class);

  private final List<String> keywords = Lists.newArrayList();

  @Override
  public Condition visitExpression(SearchExpressionParser.ExpressionContext ctx) {
    List<SearchExpressionParser.AndExpressionContext> andExpressions = ctx.andExpression();

    if (andExpressions.size() == 1) {
      return visit(andExpressions.get(0));
    }

    List<Condition> conditions =
        andExpressions.stream()
            .map(this::visit)
            .filter(condition -> condition != null)
            .collect(Collectors.toList());

    return new Condition.OrCondition(conditions);
  }

  @Override
  public Condition visitAndExpression(SearchExpressionParser.AndExpressionContext ctx) {
    List<SearchExpressionParser.TermContext> terms = ctx.term();

    if (terms.size() == 1) {
      return visit(terms.get(0));
    }

    List<Condition> conditions =
        terms.stream()
            .map(this::visit)
            .filter(condition -> condition != null)
            .collect(Collectors.toList());

    return new Condition.AndCondition(conditions);
  }

  @Override
  public Condition visitTerm(SearchExpressionParser.TermContext ctx) {
    if (ctx.factor() != null) {
      Condition innerCondition = visit(ctx.factor());
      return new Condition.NotCondition(innerCondition);
    }

    if (ctx.literal() != null) {
      return visit(ctx.literal());
    }

    return visit(ctx.expression());
  }

  @Override
  public Condition visitFactor(SearchExpressionParser.FactorContext ctx) {
    String field = ctx.ID().getText();
    if (field.equals("name")) {
      field = "entity_name";
    }
    List<SearchExpressionParser.AtomContext> valueNodes = ctx.value().atom();

    if (valueNodes.size() == 1) {
      String value = atomValue(valueNodes.get(0));
      return new Condition.TermCondition(field, value);
    } else {
      List<String> values =
          valueNodes.stream().map(ConditionBuilderVisitor::atomValue).collect(Collectors.toList());
      return new Condition.InCondition(field, values);
    }
  }

  @Override
  public Condition visitLiteral(LiteralContext ctx) {
    if (ctx.atom() != null) {
      String value = atomValue(ctx.atom());
      keywords.add(value);
      return new Condition.KeywordCondition(value, ctx.atom().STRING() != null);
    }

    return visit(ctx.factor());
  }

  public static QueryCondition buildQueryCondition(String expression) {
    if (expression == null || expression.isBlank()) {
      return new QueryCondition(null, List.of());
    }
    BaseErrorListener errors =
        new BaseErrorListener() {
          @Override
          public void syntaxError(
              Recognizer<?, ?> recognizer,
              Object offendingSymbol,
              int line,
              int charPositionInLine,
              String msg,
              RecognitionException e) {
            LOG.warn(
                "Invalid search syntax at line {}, column {}: {}",
                line,
                charPositionInLine + 1,
                msg,
                e);
            throw new QueryParseException(
                "The search could not be run. "
                    + syntaxErrorMessage(recognizer, offendingSymbol, e),
                e);
          }
        };
    CharStream charStream = CharStreams.fromString(expression);
    SearchExpressionLexer lexer = new SearchExpressionLexer(charStream);
    lexer.removeErrorListeners();
    lexer.addErrorListener(errors);
    SearchExpressionParser parser = new SearchExpressionParser(new CommonTokenStream(lexer));
    parser.removeErrorListeners();
    parser.addErrorListener(errors);
    SearchExpressionParser.QueryContext tree = parser.query();
    ConditionBuilderVisitor visitor = new ConditionBuilderVisitor();
    Condition condition = tree.expression() == null ? null : visitor.visit(tree.expression());
    return new QueryCondition(condition, visitor.keywords);
  }

  private static String syntaxErrorMessage(
      Recognizer<?, ?> recognizer,
      @Nullable Object offendingSymbol,
      @Nullable RecognitionException error) {
    if (recognizer instanceof SearchExpressionLexer lexer
        && error instanceof LexerNoViableAltException lexicalError) {
      CharStream input = lexer.getInputStream();
      int startIndex = lexicalError.getStartIndex();
      String character = input.getText(Interval.of(startIndex, startIndex));
      if (character.equals("\"")) {
        // The lexer stops right before the character it could not consume. A quoted term can only
        // fail at EOF, at a line break, or at the character following an unescaped backslash. The
        // backslash case is checked first: for "a\ the quote is not simply unterminated, and
        // appending a double quote would only produce another (escaped) unterminated string.
        String body = input.getText(Interval.of(startIndex + 1, input.index() - 1));
        if (endsWithUnescapedBackslash(body)) {
          return "Invalid escape in a quoted search term. Only double quotes and backslashes can be escaped.";
        }
        if (input.LA(1) == IntStream.EOF) {
          return "Close the quoted search term with a double quote.";
        }
        return "Remove the line break inside the quoted search term.";
      }
      return "Remove the unsupported character '"
          + displayCharacter(character)
          + "' or put the search term in double quotes.";
    }

    if (recognizer instanceof SearchExpressionParser parser
        && offendingSymbol instanceof Token token) {
      Token previous = parser.getTokenStream().LT(-1);
      if (previous != null) {
        switch (previous.getType()) {
          case SearchExpressionParser.COLON:
          case SearchExpressionParser.COMMA:
            return "Enter a search value after '" + previous.getText() + "'.";
          case SearchExpressionParser.AND:
          case SearchExpressionParser.OR:
            return "Enter a search term after '" + previous.getText() + "'.";
          default:
            break;
        }
      }
      if (token.getType() == Token.EOF) {
        if (parser.getExpectedTokens().contains(SearchExpressionParser.RPAREN)) {
          return "Add the missing closing parenthesis ')'.";
        }
        return "Complete the search expression before running it again.";
      }
      if (token.getType() == SearchExpressionParser.RPAREN) {
        return "Check the closing parenthesis ')'. Each parenthesis must enclose a search expression.";
      }
      return "Check the search syntax near '" + token.getText() + "'.";
    }
    return "Check the search syntax and run again.";
  }

  /** Returns whether the quoted-string body ends with a backslash that does not escape anything. */
  private static boolean endsWithUnescapedBackslash(String body) {
    int trailingBackslashes = 0;
    for (int i = body.length() - 1; i >= 0 && body.charAt(i) == '\\'; i--) {
      trailingBackslashes++;
    }
    return trailingBackslashes % 2 == 1;
  }

  /**
   * Renders a rejected character for an error message. Whitespace, control and other characters
   * without a dependable visible glyph (including format, unassigned and private-use characters)
   * are shown as their Unicode code point.
   */
  private static String displayCharacter(String character) {
    int codePoint = character.codePointAt(0);
    int type = Character.getType(codePoint);
    boolean useCodePointNotation =
        Character.isWhitespace(codePoint)
            || Character.isSpaceChar(codePoint)
            || Character.isISOControl(codePoint)
            || type == Character.FORMAT
            || type == Character.UNASSIGNED
            || type == Character.PRIVATE_USE;
    return useCodePointNotation ? String.format("U+%04X", codePoint) : character;
  }

  private static String atomValue(SearchExpressionParser.AtomContext atom) {
    String text = atom.getText();
    if (atom.STRING() == null) {
      return text;
    }
    StringBuilder result = new StringBuilder();
    for (int i = 1; i < text.length() - 1; i++) {
      char character = text.charAt(i);
      if (character == '\\') {
        character = text.charAt(++i);
      }
      result.append(character);
    }
    if (result.toString().isBlank()) {
      throw new QueryParseException("Quoted search terms must not be blank", null);
    }
    return result.toString();
  }

  public static class QueryCondition {
    private final Condition condition;
    private final List<String> keywords;

    public QueryCondition(Condition condition, List<String> keywords) {
      this.condition = condition;
      this.keywords = keywords;
    }

    /**
     * Returns the complete expression, including free-text terms and field filters.
     *
     * @return the expression, or null for an empty query
     */
    public Condition getCondition() {
      return condition;
    }

    /**
     * Returns free-text terms in encounter order, without their boolean relationships.
     *
     * @return the terms; use {@link #getCondition()} to execute the query
     */
    public List<String> getKeywords() {
      return keywords;
    }
  }

  static class QueryParseException extends IllegalArgumentException {
    public QueryParseException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
