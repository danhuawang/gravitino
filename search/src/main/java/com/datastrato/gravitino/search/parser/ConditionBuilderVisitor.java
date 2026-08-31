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
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

public class ConditionBuilderVisitor extends SearchExpressionBaseVisitor<Condition> {

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
    if (ctx.MINUS() != null) {
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
    List<TerminalNode> valueNodes = ctx.value().ID();

    if (valueNodes.size() == 1) {
      String value = valueNodes.get(0).getText();
      return new Condition.TermCondition(field, value);
    } else {
      List<String> values =
          valueNodes.stream().map(TerminalNode::getText).collect(Collectors.toList());
      return new Condition.InCondition(field, values);
    }
  }

  @Override
  public Condition visitLiteral(LiteralContext ctx) {
    if (ctx.ID() != null) {
      keywords.add(ctx.ID().getText());
      return null;
    }

    return visit(ctx.factor());
  }

  public static QueryCondition buildQueryCondition(String expression) {
    try {
      CharStream charStream = CharStreams.fromString(expression);
      SearchExpressionLexer lexer = new SearchExpressionLexer(charStream);
      CommonTokenStream tokens = new CommonTokenStream(lexer);
      SearchExpressionParser parser = new SearchExpressionParser(tokens);

      ParseTree tree = parser.query();
      ConditionBuilderVisitor visitor = new ConditionBuilderVisitor();
      Condition c = visitor.visit(tree.getChild(0));
      return new QueryCondition(c, visitor.keywords);
    } catch (Exception e) {
      throw new QueryParseException("Parse fail: " + expression, e);
    }
  }

  public static class QueryCondition {
    private final Condition condition;
    private final List<String> keywords;

    public QueryCondition(Condition condition, List<String> keywords) {
      this.condition = condition;
      this.keywords = keywords;
    }

    public Condition getCondition() {
      return condition;
    }

    public List<String> getKeywords() {
      return keywords;
    }
  }

  static class QueryParseException extends RuntimeException {
    public QueryParseException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
