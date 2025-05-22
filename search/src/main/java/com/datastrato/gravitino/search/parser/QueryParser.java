/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.parser;

import com.datastrato.gravitino.search.parser.Condition.AndCondition;
import com.datastrato.gravitino.search.parser.Condition.InCondition;
import com.datastrato.gravitino.search.parser.Condition.NotCondition;
import com.datastrato.gravitino.search.parser.Condition.OrCondition;
import com.datastrato.gravitino.search.parser.Condition.TermCondition;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

/**
 * A simple query parser that parses a query string into a condition tree. The query string can
 * contain AND, OR, and NOT operators. The syntax is as follows:
 *
 * <ul>
 *   <li>field:value
 *   <li>field:value1,value2
 *   <li>-field:value
 *   <li>field1:value1 AND field2:value2
 *   <li>field1:value1 OR field2:value2
 *   <li>field1:value1 OR -field2:value2 AND field3:value3
 * </ul>
 */
public class QueryParser {
  private static final String AND = "AND";
  private static final String OR = "OR";
  private static final String NOT = "-";
  private static final String SPACE = " ";

  private static final Set<String> FILTER_OPERATORS = Sets.newHashSet(AND, OR);

  public static Condition parse(String query) {
    query = query.replaceAll("\\s+", SPACE).trim();
    Tokenizer tokenizer = new Tokenizer(new StringTokenizer(query, SPACE, false));
    return parseOrExpression(tokenizer);
  }

  private static Condition parseOrExpression(Tokenizer tokenizer) {
    List<Condition> conditions = new ArrayList<>();
    conditions.add(parseAndExpression(tokenizer));
    while (isNextToken(tokenizer, OR)) {
      tokenizer.consume();
      conditions.add(parseAndExpression(tokenizer));
    }
    return conditions.size() == 1 ? conditions.get(0) : new OrCondition(conditions);
  }

  private static Condition parseAndExpression(Tokenizer tokenizer) {
    List<Condition> conditions = new ArrayList<>();
    conditions.add(parsePrimary(tokenizer));
    while (isNextToken(tokenizer, AND)) {
      tokenizer.consume();
      conditions.add(parsePrimary(tokenizer));
    }
    return conditions.size() == 1 ? conditions.get(0) : new AndCondition(conditions);
  }

  private static Condition parsePrimary(Tokenizer tokenizer) {
    boolean isNegative = false;
    String currentToken = tokenizer.lookahead;

    if (currentToken != null && currentToken.startsWith(NOT)) {
      isNegative = true;
      tokenizer.lookahead = currentToken.substring(1);
      if (tokenizer.lookahead.isEmpty()) {
        tokenizer.consume();
      }
    } else if (isNextToken(tokenizer, NOT)) {
      isNegative = true;
      tokenizer.consume();
    }

    String fieldPart = tokenizer.nextToken();
    String[] parts = splitFieldValue(fieldPart);
    Condition condition = createCondition(parts[0], parts[1]);
    return isNegative ? new NotCondition(condition) : condition;
  }

  private static Condition createCondition(String field, String value) {
    if (value.contains(",")) {
      return new InCondition(field, Lists.newArrayList(value.split(",")));
    } else {
      return new TermCondition(field, value);
    }
  }

  private static String[] splitFieldValue(String fieldPart) {
    int colonIndex = fieldPart.indexOf(':');
    if (colonIndex == -1 || colonIndex == 0) {
      throw new IllegalArgumentException("Invalid field format: " + fieldPart);
    }
    String field = fieldPart.substring(0, colonIndex);
    String value = fieldPart.substring(colonIndex + 1);
    return new String[] {field, value};
  }

  private static boolean isNextToken(Tokenizer tokenizer, String op) {
    return tokenizer.lookahead != null && op.equalsIgnoreCase(tokenizer.lookahead);
  }

  static class Tokenizer {
    private final StringTokenizer tokenizer;
    private String lookahead;

    public Tokenizer(StringTokenizer tokenizer) {
      this.tokenizer = tokenizer;
      advance();
    }

    private void advance() {
      lookahead = tokenizer.hasMoreTokens() ? tokenizer.nextToken() : null;
    }

    public void consume() {
      if (lookahead == null) throw new IllegalStateException("No more tokens");
      advance();
    }

    public String nextToken() {
      String token = lookahead;
      advance();
      return token;
    }
  }

  public static Pair<String, String> parserQuery(String query) {
    if (StringUtils.isBlank(query)) {
      return Pair.of("", "");
    }

    Iterable<String> iterable = Splitter.on(" ").omitEmptyStrings().trimResults().split(query);

    List<String> keywords = Lists.newArrayList();
    List<String> filters = Lists.newArrayList();

    for (String s : iterable) {
      if (!s.contains(":") && !FILTER_OPERATORS.contains(s)) {
        keywords.add(s);
      } else {
        filters.add(s);
      }
    }

    String keyword = Joiner.on(" ").join(keywords);
    String filter = Joiner.on(" ").join(filters);
    return Pair.of(keyword, filter);
  }
}
