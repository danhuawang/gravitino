/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.parser;

import static com.datastrato.gravitino.search.parser.ConditionBuilderVisitor.buildQueryCondition;

import com.datastrato.gravitino.search.parser.Condition.AndCondition;
import com.datastrato.gravitino.search.parser.Condition.InCondition;
import com.datastrato.gravitino.search.parser.Condition.NotCondition;
import com.datastrato.gravitino.search.parser.Condition.OrCondition;
import com.datastrato.gravitino.search.parser.Condition.TermCondition;
import com.datastrato.gravitino.search.parser.ConditionBuilderVisitor.QueryCondition;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestQueryParser {

  @Test
  void testComplicatedQuery() {
    assertExpression(
        "field1:value1 lucy lily (field2:value2 OR field3:value3)",
        "AND(field1:value1,kw(lucy),kw(lily),OR(field2:value2,field3:value3))");
    assertExpression(
        "field1:value1 (field2:value2 lucy OR lily field3:value3)",
        "AND(field1:value1,OR(AND(field2:value2,kw(lucy)),AND(kw(lily),field3:value3)))");
    assertExpression(
        "field1:value1 AND (field2:value2 lucy OR lily field3:value3) OR field4:value4",
        "OR(AND(field1:value1,OR(AND(field2:value2,kw(lucy)),AND(kw(lily),field3:value3))),field4:value4)");
  }

  @Test
  public void testParser() {
    String query = "field1:value1 AND field2:value2 OR field3:value3";
    Condition condition = buildQueryCondition(query).getCondition();
    Assertions.assertInstanceOf(OrCondition.class, condition);
    OrCondition orCondition = (OrCondition) condition;
    Assertions.assertEquals(orCondition.getConditions().size(), 2);
    Assertions.assertInstanceOf(AndCondition.class, orCondition.getConditions().get(0));
    Condition.AndCondition andCondition = (AndCondition) orCondition.getConditions().get(0);
    Assertions.assertEquals(andCondition.getConditions().size(), 2);
    Assertions.assertInstanceOf(TermCondition.class, andCondition.getConditions().get(0));
    Condition.TermCondition termCondition1 = (TermCondition) andCondition.getConditions().get(0);
    Assertions.assertEquals(termCondition1.getField(), "field1");
    Assertions.assertEquals(termCondition1.getValue(), "value1");
    Condition.TermCondition termCondition2 = (TermCondition) andCondition.getConditions().get(1);
    Assertions.assertEquals(termCondition2.getField(), "field2");
    Assertions.assertEquals(termCondition2.getValue(), "value2");
    Condition.TermCondition termCondition = (TermCondition) orCondition.getConditions().get(1);
    Assertions.assertEquals(termCondition.getField(), "field3");
    Assertions.assertEquals(termCondition.getValue(), "value3");

    query = "field1:value1 OR field2:value2 AND field3:value3";
    condition = buildQueryCondition(query).getCondition();
    Assertions.assertInstanceOf(OrCondition.class, condition);
    orCondition = (OrCondition) condition;
    Assertions.assertEquals(orCondition.getConditions().size(), 2);
    Assertions.assertInstanceOf(AndCondition.class, orCondition.getConditions().get(1));
    andCondition = (AndCondition) orCondition.getConditions().get(1);
    Assertions.assertEquals(andCondition.getConditions().size(), 2);
    Assertions.assertInstanceOf(TermCondition.class, andCondition.getConditions().get(0));
    termCondition1 = (TermCondition) andCondition.getConditions().get(0);
    Assertions.assertEquals(termCondition1.getField(), "field2");
    Assertions.assertEquals(termCondition1.getValue(), "value2");
    termCondition2 = (TermCondition) andCondition.getConditions().get(1);
    Assertions.assertEquals(termCondition2.getField(), "field3");
    Assertions.assertEquals(termCondition2.getValue(), "value3");
    termCondition = (TermCondition) orCondition.getConditions().get(0);
    Assertions.assertEquals(termCondition.getField(), "field1");
    Assertions.assertEquals(termCondition.getValue(), "value1");

    query = "field1:value1 OR -field2:value2 AND field3:value3";
    condition = buildQueryCondition(query).getCondition();
    Assertions.assertInstanceOf(OrCondition.class, condition);
    orCondition = (OrCondition) condition;
    Assertions.assertEquals(orCondition.getConditions().size(), 2);
    Assertions.assertInstanceOf(AndCondition.class, orCondition.getConditions().get(1));
    andCondition = (AndCondition) orCondition.getConditions().get(1);
    Assertions.assertEquals(andCondition.getConditions().size(), 2);

    Assertions.assertInstanceOf(NotCondition.class, andCondition.getConditions().get(0));
    NotCondition notCondition = (NotCondition) andCondition.getConditions().get(0);
    termCondition = (TermCondition) notCondition.getCondition();
    Assertions.assertEquals(termCondition.getField(), "field2");
    Assertions.assertEquals(termCondition.getValue(), "value2");

    termCondition2 = (TermCondition) andCondition.getConditions().get(1);
    Assertions.assertEquals(termCondition2.getField(), "field3");
    Assertions.assertEquals(termCondition2.getValue(), "value3");
    termCondition = (TermCondition) orCondition.getConditions().get(0);
    Assertions.assertEquals(termCondition.getField(), "field1");
    Assertions.assertEquals(termCondition.getValue(), "value1");

    query = "field1:value1,value4,value5 OR field2:value2 AND field3:value3";
    condition = buildQueryCondition(query).getCondition();
    Assertions.assertInstanceOf(OrCondition.class, condition);
    orCondition = (OrCondition) condition;
    Assertions.assertEquals(orCondition.getConditions().size(), 2);
    Assertions.assertInstanceOf(AndCondition.class, orCondition.getConditions().get(1));
    andCondition = (AndCondition) orCondition.getConditions().get(1);
    Assertions.assertEquals(andCondition.getConditions().size(), 2);
    Assertions.assertInstanceOf(InCondition.class, orCondition.getConditions().get(0));
    InCondition inCondition = (InCondition) orCondition.getConditions().get(0);
    Assertions.assertEquals(inCondition.getField(), "field1");
    Assertions.assertEquals(inCondition.getValues().size(), 3);
    Assertions.assertEquals(inCondition.getValues().get(0), "value1");
    Assertions.assertEquals(inCondition.getValues().get(1), "value4");
    Assertions.assertEquals(inCondition.getValues().get(2), "value5");

    termCondition1 = (TermCondition) andCondition.getConditions().get(0);
    Assertions.assertEquals(termCondition1.getField(), "field2");
    Assertions.assertEquals(termCondition1.getValue(), "value2");

    termCondition2 = (TermCondition) andCondition.getConditions().get(1);
    Assertions.assertEquals(termCondition2.getField(), "field3");
    Assertions.assertEquals(termCondition2.getValue(), "value3");
  }

  @Test
  void testNot() {
    String query = "-field1:value1";
    Condition condition = buildQueryCondition(query).getCondition();
    Assertions.assertInstanceOf(NotCondition.class, condition);
    NotCondition notCondition = (NotCondition) condition;
    Assertions.assertInstanceOf(TermCondition.class, notCondition.getCondition());

    query = "-field1:value1 AND -field2:value2 OR -field3:value3";
    condition = buildQueryCondition(query).getCondition();
    Assertions.assertInstanceOf(OrCondition.class, condition);
    OrCondition orCondition = (OrCondition) condition;
    Assertions.assertEquals(orCondition.getConditions().size(), 2);
    Assertions.assertInstanceOf(AndCondition.class, orCondition.getConditions().get(0));
    AndCondition andCondition = (AndCondition) orCondition.getConditions().get(0);
    Assertions.assertEquals(andCondition.getConditions().size(), 2);
    Assertions.assertInstanceOf(NotCondition.class, andCondition.getConditions().get(0));
    NotCondition notCondition1 = (NotCondition) andCondition.getConditions().get(0);
    Assertions.assertInstanceOf(TermCondition.class, notCondition1.getCondition());
    TermCondition termCondition = (TermCondition) notCondition1.getCondition();
    Assertions.assertEquals(termCondition.getField(), "field1");
    Assertions.assertEquals(termCondition.getValue(), "value1");

    NotCondition notCondition2 = (NotCondition) andCondition.getConditions().get(1);
    Assertions.assertInstanceOf(TermCondition.class, notCondition2.getCondition());
    TermCondition termCondition2 = (TermCondition) notCondition2.getCondition();
    Assertions.assertEquals(termCondition2.getField(), "field2");
    Assertions.assertEquals(termCondition2.getValue(), "value2");
    Assertions.assertInstanceOf(NotCondition.class, andCondition.getConditions().get(0));
    notCondition1 = (NotCondition) andCondition.getConditions().get(0);
    termCondition = (TermCondition) notCondition1.getCondition();
    Assertions.assertEquals(termCondition.getField(), "field1");
    Assertions.assertEquals(termCondition.getValue(), "value1");
    NotCondition notCondition3 = (NotCondition) orCondition.getConditions().get(1);
    Assertions.assertInstanceOf(TermCondition.class, notCondition3.getCondition());
    TermCondition termCondition3 = (TermCondition) notCondition3.getCondition();
    Assertions.assertEquals(termCondition3.getField(), "field3");
    Assertions.assertEquals(termCondition3.getValue(), "value3");
  }

  @Test
  void testOr() {
    String query = "field1:value1 OR field2:value2,value4,value5 OR field3:value3";
    Condition condition = buildQueryCondition(query).getCondition();
    Assertions.assertInstanceOf(OrCondition.class, condition);
    OrCondition orCondition = (OrCondition) condition;
    Assertions.assertEquals(orCondition.getConditions().size(), 3);
    Assertions.assertInstanceOf(InCondition.class, orCondition.getConditions().get(1));
    InCondition inCondition = (InCondition) orCondition.getConditions().get(1);

    Assertions.assertEquals(inCondition.getField(), "field2");
    Assertions.assertEquals(inCondition.getValues().size(), 3);
    Assertions.assertEquals(inCondition.getValues().get(0), "value2");
    Assertions.assertEquals(inCondition.getValues().get(1), "value4");
    Assertions.assertEquals(inCondition.getValues().get(2), "value5");

    TermCondition termCondition1 = (TermCondition) orCondition.getConditions().get(0);
    Assertions.assertEquals(termCondition1.getField(), "field1");
    Assertions.assertEquals(termCondition1.getValue(), "value1");
    TermCondition termCondition2 = (TermCondition) orCondition.getConditions().get(2);
    Assertions.assertEquals(termCondition2.getField(), "field3");
    Assertions.assertEquals(termCondition2.getValue(), "value3");
    Assertions.assertEquals(termCondition2.getField(), "field3");
  }

  @Test
  void testNoAnd() {
    String query = "field1:value1 field2:value2";
    Condition condition = buildQueryCondition(query).getCondition();
    Assertions.assertInstanceOf(AndCondition.class, condition);
    AndCondition andCondition = (AndCondition) condition;
    Assertions.assertEquals(andCondition.getConditions().size(), 2);
    TermCondition termCondition1 = (TermCondition) andCondition.getConditions().get(0);
    Assertions.assertEquals(termCondition1.getField(), "field1");
    Assertions.assertEquals(termCondition1.getValue(), "value1");
    TermCondition termCondition2 = (TermCondition) andCondition.getConditions().get(1);
    Assertions.assertEquals(termCondition2.getField(), "field2");
    Assertions.assertEquals(termCondition2.getValue(), "value2");

    query = "field1:value1 OR field2:value2 field3:value3";
    condition = buildQueryCondition(query).getCondition();
    Assertions.assertInstanceOf(OrCondition.class, condition);
    OrCondition orCondition = (OrCondition) condition;
    Assertions.assertEquals(orCondition.getConditions().size(), 2);
    TermCondition termCondition = (TermCondition) orCondition.getConditions().get(0);
    Assertions.assertEquals(termCondition.getField(), "field1");
    Assertions.assertEquals(termCondition.getValue(), "value1");
    Assertions.assertInstanceOf(AndCondition.class, orCondition.getConditions().get(1));
    AndCondition andCondition2 = (AndCondition) orCondition.getConditions().get(1);
    Assertions.assertEquals(andCondition2.getConditions().size(), 2);
    TermCondition termCondition3 = (TermCondition) andCondition2.getConditions().get(0);
    Assertions.assertEquals(termCondition3.getField(), "field2");
    Assertions.assertEquals(termCondition3.getValue(), "value2");
    TermCondition termCondition4 = (TermCondition) andCondition2.getConditions().get(1);
    Assertions.assertEquals(termCondition4.getField(), "field3");
    Assertions.assertEquals(termCondition4.getValue(), "value3");

    query = "field1:value1 OR field2:value2 OR field3:value3";
    condition = buildQueryCondition(query).getCondition();
    Assertions.assertInstanceOf(OrCondition.class, condition);
    OrCondition orCondition3 = (OrCondition) condition;
    Assertions.assertEquals(orCondition3.getConditions().size(), 3);

    TermCondition termCondition5 = (TermCondition) orCondition3.getConditions().get(0);
    Assertions.assertEquals(termCondition5.getField(), "field1");
    Assertions.assertEquals(termCondition5.getValue(), "value1");
    TermCondition termCondition6 = (TermCondition) orCondition3.getConditions().get(1);
    Assertions.assertEquals(termCondition6.getField(), "field2");
    Assertions.assertEquals(termCondition6.getValue(), "value2");
    TermCondition termCondition7 = (TermCondition) orCondition3.getConditions().get(2);
    Assertions.assertEquals(termCondition7.getField(), "field3");
    Assertions.assertEquals(termCondition7.getValue(), "value3");
  }

  @Test
  void testBrace() {
    String query = "field1:value1 OR (field2:value2 AND field3:value3)";
    Condition condition = buildQueryCondition(query).getCondition();
    Assertions.assertInstanceOf(OrCondition.class, condition);
    OrCondition orCondition = (OrCondition) condition;
    Assertions.assertEquals(orCondition.getConditions().size(), 2);
    TermCondition termCondition1 = (TermCondition) orCondition.getConditions().get(0);
    Assertions.assertEquals(termCondition1.getField(), "field1");
    Assertions.assertEquals(termCondition1.getValue(), "value1");

    Assertions.assertInstanceOf(AndCondition.class, orCondition.getConditions().get(1));
    AndCondition andCondition = (AndCondition) orCondition.getConditions().get(1);
    Assertions.assertEquals(andCondition.getConditions().size(), 2);

    TermCondition termCondition2 = (TermCondition) andCondition.getConditions().get(0);
    Assertions.assertEquals(termCondition2.getField(), "field2");
    Assertions.assertEquals(termCondition2.getValue(), "value2");

    TermCondition termCondition3 = (TermCondition) andCondition.getConditions().get(1);
    Assertions.assertEquals(termCondition3.getField(), "field3");
    Assertions.assertEquals(termCondition3.getValue(), "value3");

    query = "field1:value1 AND (field2:value2 OR field3:value3) AND field4:value4";
    condition = buildQueryCondition(query).getCondition();
    Assertions.assertInstanceOf(AndCondition.class, condition);
    AndCondition andCondition2 = (AndCondition) condition;
    Assertions.assertEquals(andCondition2.getConditions().size(), 3);
    TermCondition termCondition4 = (TermCondition) andCondition2.getConditions().get(0);
    Assertions.assertEquals(termCondition4.getField(), "field1");
    Assertions.assertEquals(termCondition4.getValue(), "value1");
    Assertions.assertInstanceOf(OrCondition.class, andCondition2.getConditions().get(1));
    OrCondition orCondition2 = (OrCondition) andCondition2.getConditions().get(1);
    Assertions.assertEquals(orCondition2.getConditions().size(), 2);
    TermCondition termCondition5 = (TermCondition) orCondition2.getConditions().get(0);
    Assertions.assertEquals(termCondition5.getField(), "field2");
    Assertions.assertEquals(termCondition5.getValue(), "value2");
    TermCondition termCondition6 = (TermCondition) orCondition2.getConditions().get(1);
    Assertions.assertEquals(termCondition6.getField(), "field3");
    Assertions.assertEquals(termCondition6.getValue(), "value3");
    TermCondition termCondition7 = (TermCondition) andCondition2.getConditions().get(2);
    Assertions.assertEquals(termCondition7.getField(), "field4");
    Assertions.assertEquals(termCondition7.getValue(), "value4");

    query =
        "field1:value1 AND (field2:value2 OR field3:value3) AND (field4:value4 OR field5:value5)";
    condition = buildQueryCondition(query).getCondition();
    Assertions.assertInstanceOf(AndCondition.class, condition);
    AndCondition andCondition3 = (AndCondition) condition;
    Assertions.assertEquals(andCondition3.getConditions().size(), 3);
    TermCondition termCondition8 = (TermCondition) andCondition3.getConditions().get(0);
    Assertions.assertEquals(termCondition8.getField(), "field1");
    Assertions.assertEquals(termCondition8.getValue(), "value1");
    Assertions.assertInstanceOf(OrCondition.class, andCondition3.getConditions().get(1));
    OrCondition orCondition3 = (OrCondition) andCondition3.getConditions().get(1);
    Assertions.assertEquals(orCondition3.getConditions().size(), 2);
    TermCondition termCondition9 = (TermCondition) orCondition3.getConditions().get(0);
    Assertions.assertEquals(termCondition9.getField(), "field2");
    Assertions.assertEquals(termCondition9.getValue(), "value2");
    TermCondition termCondition10 = (TermCondition) orCondition3.getConditions().get(1);
    Assertions.assertEquals(termCondition10.getField(), "field3");
    Assertions.assertEquals(termCondition10.getValue(), "value3");
    Assertions.assertInstanceOf(OrCondition.class, andCondition3.getConditions().get(2));
    OrCondition orCondition4 = (OrCondition) andCondition3.getConditions().get(2);
    Assertions.assertEquals(orCondition4.getConditions().size(), 2);
    TermCondition termCondition11 = (TermCondition) orCondition4.getConditions().get(0);
    Assertions.assertEquals(termCondition11.getField(), "field4");
    Assertions.assertEquals(termCondition11.getValue(), "value4");
    TermCondition termCondition12 = (TermCondition) orCondition4.getConditions().get(1);
    Assertions.assertEquals(termCondition12.getField(), "field5");
    Assertions.assertEquals(termCondition12.getValue(), "value5");
  }

  @Test
  void testSupportChinese() {
    String query = "字段1:值1 AND 字段2:值2 OR 字段3:值3";
    Condition condition = buildQueryCondition(query).getCondition();
    Assertions.assertInstanceOf(OrCondition.class, condition);
    OrCondition orCondition = (OrCondition) condition;
    Assertions.assertEquals(orCondition.getConditions().size(), 2);
    Assertions.assertInstanceOf(AndCondition.class, orCondition.getConditions().get(0));
    AndCondition andCondition = (AndCondition) orCondition.getConditions().get(0);
    Assertions.assertEquals(andCondition.getConditions().size(), 2);
    Assertions.assertInstanceOf(TermCondition.class, andCondition.getConditions().get(0));
    TermCondition termCondition1 = (TermCondition) andCondition.getConditions().get(0);
    Assertions.assertEquals(termCondition1.getField(), "字段1");
    Assertions.assertEquals(termCondition1.getValue(), "值1");
    TermCondition termCondition2 = (TermCondition) andCondition.getConditions().get(1);
    Assertions.assertEquals(termCondition2.getField(), "字段2");
    Assertions.assertEquals(termCondition2.getValue(), "值2");
    TermCondition termCondition3 = (TermCondition) orCondition.getConditions().get(1);
    Assertions.assertEquals(termCondition3.getField(), "字段3");
    Assertions.assertEquals(termCondition3.getValue(), "值3");

    query = "字段1:值1 OR 字段2:值2 AND 字段3:值3";
    condition = buildQueryCondition(query).getCondition();
    Assertions.assertInstanceOf(OrCondition.class, condition);
    orCondition = (OrCondition) condition;
    Assertions.assertEquals(orCondition.getConditions().size(), 2);
    Assertions.assertInstanceOf(AndCondition.class, orCondition.getConditions().get(1));
    andCondition = (AndCondition) orCondition.getConditions().get(1);
    Assertions.assertEquals(andCondition.getConditions().size(), 2);
    Assertions.assertInstanceOf(TermCondition.class, andCondition.getConditions().get(0));
    TermCondition termCondition4 = (TermCondition) andCondition.getConditions().get(0);
    Assertions.assertEquals(termCondition4.getField(), "字段2");
    Assertions.assertEquals(termCondition4.getValue(), "值2");
    TermCondition termCondition5 = (TermCondition) andCondition.getConditions().get(1);
    Assertions.assertEquals(termCondition5.getField(), "字段3");
    Assertions.assertEquals(termCondition5.getValue(), "值3");
    TermCondition termCondition6 = (TermCondition) orCondition.getConditions().get(0);
    Assertions.assertEquals(termCondition6.getField(), "字段1");
    Assertions.assertEquals(termCondition6.getValue(), "值1");

    query = "字段1:值1 字段2:值2 AND 字段3:值3";
    condition = buildQueryCondition(query).getCondition();
    Assertions.assertInstanceOf(AndCondition.class, condition);
    AndCondition andCondition2 = (AndCondition) condition;
    Assertions.assertEquals(andCondition2.getConditions().size(), 3);
    TermCondition termCondition7 = (TermCondition) andCondition2.getConditions().get(0);
    Assertions.assertEquals(termCondition7.getField(), "字段1");
    Assertions.assertEquals(termCondition7.getValue(), "值1");
    TermCondition termCondition8 = (TermCondition) andCondition2.getConditions().get(1);
    Assertions.assertEquals(termCondition8.getField(), "字段2");
    Assertions.assertEquals(termCondition8.getValue(), "值2");
    TermCondition termCondition9 = (TermCondition) andCondition2.getConditions().get(2);
    Assertions.assertEquals(termCondition9.getField(), "字段3");
    Assertions.assertEquals(termCondition9.getValue(), "值3");
  }

  @Test
  void testQueryParser() {
    assertExpression("alpha beta", "AND(kw(alpha),kw(beta))");
    assertExpression("alpha  beta", "AND(kw(alpha),kw(beta))");
    assertExpression("alpha\tbeta", "AND(kw(alpha),kw(beta))");
    assertExpression("alpha\nbeta", "AND(kw(alpha),kw(beta))");
    assertExpression("alpha AND beta", "AND(kw(alpha),kw(beta))");
    assertExpression("alpha OR beta", "OR(kw(alpha),kw(beta))");
    assertExpression("alpha beta OR gamma", "OR(AND(kw(alpha),kw(beta)),kw(gamma))");
    assertExpression("alpha (beta OR gamma)", "AND(kw(alpha),OR(kw(beta),kw(gamma)))");
    assertExpression("alpha OR entity_name:beta", "OR(kw(alpha),entity_name:beta)");
    assertExpression("alpha -entity_name:beta", "AND(kw(alpha),NOT(entity_name:beta))");
    assertExpression("字段1:值1 搜索 内容", "AND(字段1:值1,kw(搜索),kw(内容))");
    QueryCondition parsed = buildQueryCondition("alpha OR beta");
    Assertions.assertEquals(List.of("alpha", "beta"), parsed.getKeywords());
  }

  @Test
  void testKeywordsWithInConditions() {
    assertExpression("entity_name:alpha,beta", "entity_name:[alpha,beta]");
    assertExpression("needle entity_name:alpha,beta", "AND(kw(needle),entity_name:[alpha,beta])");
    assertExpression("needle OR entity_name:alpha,beta", "OR(kw(needle),entity_name:[alpha,beta])");
    assertExpression(
        "needle -entity_name:alpha,beta", "AND(kw(needle),NOT(entity_name:[alpha,beta]))");
    assertExpression(
        "needle (entity_name:alpha,beta OR gamma)",
        "AND(kw(needle),OR(entity_name:[alpha,beta],kw(gamma)))");
  }

  @Test
  void testNoCondition() {
    Assertions.assertNull(buildQueryCondition("").getCondition());
    Assertions.assertNull(buildQueryCondition(" \t\n ").getCondition());
    Assertions.assertTrue(buildQueryCondition("").getKeywords().isEmpty());
  }

  private static void assertExpression(String expression, String expected) {
    Assertions.assertEquals(
        expected, describe(buildQueryCondition(expression).getCondition()), expression);
  }

  private static String describe(Condition condition) {
    if (condition instanceof Condition.KeywordCondition) {
      return "kw(" + ((Condition.KeywordCondition) condition).getKeyword() + ")";
    }
    if (condition instanceof AndCondition) {
      return "AND("
          + ((AndCondition) condition)
              .getConditions().stream()
                  .map(TestQueryParser::describe)
                  .collect(Collectors.joining(","))
          + ")";
    }
    if (condition instanceof OrCondition) {
      return "OR("
          + ((OrCondition) condition)
              .getConditions().stream()
                  .map(TestQueryParser::describe)
                  .collect(Collectors.joining(","))
          + ")";
    }
    if (condition instanceof NotCondition) {
      return "NOT(" + describe(((NotCondition) condition).getCondition()) + ")";
    }
    if (condition instanceof InCondition) {
      InCondition in = (InCondition) condition;
      return in.getField() + ":[" + String.join(",", in.getValues()) + "]";
    }
    if (condition instanceof TermCondition) {
      TermCondition term = (TermCondition) condition;
      return term.getField() + ":" + term.getValue();
    }
    throw new AssertionError("Unsupported parsed condition: " + condition.getClass().getName());
  }
}
