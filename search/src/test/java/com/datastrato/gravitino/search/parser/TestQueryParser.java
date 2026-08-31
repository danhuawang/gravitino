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
import com.datastrato.gravitino.search.utils.FilterConditionUtils;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch._types.query_dsl.Query;

public class TestQueryParser {

  @Test
  void testComplicatedQuery() {
    String query = "field1:value1 lucy lily (field2:value2 OR field3:value3)";
    QueryCondition queryCondition = buildQueryCondition(query);
    List<String> keywords = queryCondition.getKeywords();
    Assertions.assertNotNull(keywords);
    Assertions.assertEquals(2, keywords.size());
    Assertions.assertEquals("lucy", keywords.get(0));
    Assertions.assertEquals("lily", keywords.get(1));
    Assertions.assertNotNull(queryCondition.getCondition());
    Condition condition = queryCondition.getCondition();
    Assertions.assertInstanceOf(AndCondition.class, condition);
    AndCondition andCondition = (AndCondition) condition;
    Assertions.assertEquals(andCondition.getConditions().size(), 2);
    TermCondition termCondition1 = (TermCondition) andCondition.getConditions().get(0);
    Assertions.assertEquals(termCondition1.getField(), "field1");
    Assertions.assertEquals(termCondition1.getValue(), "value1");

    Assertions.assertInstanceOf(OrCondition.class, andCondition.getConditions().get(1));
    OrCondition orCondition = (OrCondition) andCondition.getConditions().get(1);
    Assertions.assertEquals(orCondition.getConditions().size(), 2);
    TermCondition termCondition2 = (TermCondition) orCondition.getConditions().get(0);
    Assertions.assertEquals(termCondition2.getField(), "field2");
    Assertions.assertEquals(termCondition2.getValue(), "value2");
    TermCondition termCondition3 = (TermCondition) orCondition.getConditions().get(1);
    Assertions.assertEquals(termCondition3.getField(), "field3");
    Assertions.assertEquals(termCondition3.getValue(), "value3");

    query = "field1:value1 (field2:value2 lucy OR lily field3:value3)";
    queryCondition = buildQueryCondition(query);
    keywords = queryCondition.getKeywords();
    Assertions.assertNotNull(keywords);
    Assertions.assertEquals(2, keywords.size());
    Assertions.assertEquals("lucy", keywords.get(0));
    Assertions.assertEquals("lily", keywords.get(1));
    condition = queryCondition.getCondition();
    Assertions.assertInstanceOf(AndCondition.class, condition);
    andCondition = (AndCondition) condition;
    Assertions.assertEquals(andCondition.getConditions().size(), 2);
    termCondition1 = (TermCondition) andCondition.getConditions().get(0);
    Assertions.assertEquals(termCondition1.getField(), "field1");
    Assertions.assertEquals(termCondition1.getValue(), "value1");
    Assertions.assertInstanceOf(OrCondition.class, andCondition.getConditions().get(1));
    orCondition = (OrCondition) andCondition.getConditions().get(1);
    Assertions.assertEquals(orCondition.getConditions().size(), 2);

    andCondition = (AndCondition) orCondition.getConditions().get(0);
    termCondition2 = (TermCondition) andCondition.getConditions().get(0);
    Assertions.assertEquals(termCondition2.getField(), "field2");
    Assertions.assertEquals(termCondition2.getValue(), "value2");

    andCondition = (AndCondition) orCondition.getConditions().get(1);
    termCondition3 = (TermCondition) andCondition.getConditions().get(0);
    Assertions.assertEquals(termCondition3.getField(), "field3");
    Assertions.assertEquals(termCondition3.getValue(), "value3");

    query = "field1:value1 ( field2:value2 lucy OR lily field3:value3 ) field4:value4";
    queryCondition = buildQueryCondition(query);
    keywords = queryCondition.getKeywords();
    Assertions.assertNotNull(keywords);
    Assertions.assertEquals(2, keywords.size());
    Assertions.assertEquals("lucy", keywords.get(0));
    Assertions.assertEquals("lily", keywords.get(1));
    condition = queryCondition.getCondition();
    Assertions.assertInstanceOf(AndCondition.class, condition);
    andCondition = (AndCondition) condition;
    Assertions.assertEquals(andCondition.getConditions().size(), 3);
    termCondition1 = (TermCondition) andCondition.getConditions().get(0);
    Assertions.assertEquals(termCondition1.getField(), "field1");
    Assertions.assertEquals(termCondition1.getValue(), "value1");

    Assertions.assertInstanceOf(OrCondition.class, andCondition.getConditions().get(1));
    orCondition = (OrCondition) andCondition.getConditions().get(1);
    Assertions.assertEquals(orCondition.getConditions().size(), 2);

    AndCondition andCondition1 = (AndCondition) orCondition.getConditions().get(0);
    Assertions.assertEquals(1, andCondition1.getConditions().size());
    termCondition2 = (TermCondition) andCondition1.getConditions().get(0);
    Assertions.assertEquals(termCondition2.getField(), "field2");
    Assertions.assertEquals(termCondition2.getValue(), "value2");

    AndCondition andCondition2 = (AndCondition) orCondition.getConditions().get(1);
    Assertions.assertEquals(1, andCondition2.getConditions().size());
    termCondition3 = (TermCondition) andCondition2.getConditions().get(0);
    Assertions.assertEquals(termCondition3.getField(), "field3");
    Assertions.assertEquals(termCondition3.getValue(), "value3");
    TermCondition termCondition4 = (TermCondition) andCondition.getConditions().get(2);
    Assertions.assertEquals(termCondition4.getField(), "field4");
    Assertions.assertEquals(termCondition4.getValue(), "value4");

    query = "field1:value1 AND ( field2:value2 lucy OR lily field3:value3 ) OR field4:value4";
    queryCondition = buildQueryCondition(query);
    keywords = queryCondition.getKeywords();
    Assertions.assertNotNull(keywords);
    Assertions.assertEquals(2, keywords.size());
    Assertions.assertEquals("lucy", keywords.get(0));
    Assertions.assertEquals("lily", keywords.get(1));
    condition = queryCondition.getCondition();
    Assertions.assertTrue(condition instanceof OrCondition);
    OrCondition orCondition1 = (OrCondition) condition;
    Assertions.assertEquals(orCondition1.getConditions().size(), 2);
    Assertions.assertInstanceOf(AndCondition.class, orCondition1.getConditions().get(0));
    andCondition1 = (AndCondition) orCondition1.getConditions().get(0);
    Assertions.assertEquals(andCondition1.getConditions().size(), 2);
    TermCondition termCondition5 = (TermCondition) andCondition1.getConditions().get(0);
    Assertions.assertEquals(termCondition5.getField(), "field1");
    Assertions.assertEquals(termCondition5.getValue(), "value1");
    Assertions.assertInstanceOf(OrCondition.class, andCondition1.getConditions().get(1));
    OrCondition orCondition2 = (OrCondition) andCondition1.getConditions().get(1);
    Assertions.assertEquals(orCondition2.getConditions().size(), 2);

    andCondition2 = (AndCondition) orCondition2.getConditions().get(0);
    Assertions.assertEquals(andCondition2.getConditions().size(), 1);
    TermCondition termCondition6 = (TermCondition) andCondition2.getConditions().get(0);
    Assertions.assertEquals(termCondition6.getField(), "field2");
    Assertions.assertEquals(termCondition6.getValue(), "value2");

    AndCondition andCondition3 = (AndCondition) orCondition2.getConditions().get(1);
    Assertions.assertEquals(andCondition3.getConditions().size(), 1);
    TermCondition termCondition7 = (TermCondition) andCondition3.getConditions().get(0);
    Assertions.assertEquals(termCondition7.getField(), "field3");
    Assertions.assertEquals(termCondition7.getValue(), "value3");
    TermCondition termCondition8 = (TermCondition) orCondition1.getConditions().get(1);
    Assertions.assertEquals(termCondition8.getField(), "field4");
    Assertions.assertEquals(termCondition8.getValue(), "value4");

    query = "( field1:value1 AND ( field2:value2 lucy OR lily field3:value3 ) OR field4:value4)";
    queryCondition = buildQueryCondition(query);
    keywords = queryCondition.getKeywords();
    Assertions.assertNotNull(keywords);
    Assertions.assertEquals(2, keywords.size());
    Assertions.assertEquals("lucy", keywords.get(0));
    Assertions.assertEquals("lily", keywords.get(1));
    condition = queryCondition.getCondition();

    Assertions.assertInstanceOf(OrCondition.class, condition);
    OrCondition orCondition3 = (OrCondition) condition;
    Assertions.assertEquals(orCondition3.getConditions().size(), 2);
    Assertions.assertInstanceOf(AndCondition.class, orCondition3.getConditions().get(0));
    andCondition2 = (AndCondition) orCondition3.getConditions().get(0);
    Assertions.assertEquals(andCondition2.getConditions().size(), 2);
    TermCondition termCondition9 = (TermCondition) andCondition2.getConditions().get(0);
    Assertions.assertEquals(termCondition9.getField(), "field1");
    Assertions.assertEquals(termCondition9.getValue(), "value1");
    Assertions.assertInstanceOf(OrCondition.class, andCondition2.getConditions().get(1));
    OrCondition orCondition4 = (OrCondition) andCondition2.getConditions().get(1);
    Assertions.assertEquals(orCondition4.getConditions().size(), 2);

    andCondition3 = (AndCondition) orCondition4.getConditions().get(0);
    Assertions.assertEquals(andCondition3.getConditions().size(), 1);
    TermCondition termCondition10 = (TermCondition) andCondition3.getConditions().get(0);
    Assertions.assertEquals(termCondition10.getField(), "field2");
    Assertions.assertEquals(termCondition10.getValue(), "value2");
    AndCondition andCondition4 = (AndCondition) orCondition4.getConditions().get(1);
    Assertions.assertEquals(andCondition4.getConditions().size(), 1);
    TermCondition termCondition11 = (TermCondition) andCondition4.getConditions().get(0);
    Assertions.assertEquals(termCondition11.getField(), "field3");
    Assertions.assertEquals(termCondition11.getValue(), "value3");
    TermCondition termCondition12 = (TermCondition) orCondition3.getConditions().get(1);
    Assertions.assertEquals(termCondition12.getField(), "field4");
    Assertions.assertEquals(termCondition12.getValue(), "value4");
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
    String query1 = "test entity_type:MODEL hello AND catalog_name:model_catalog";
    QueryCondition queryCondition = buildQueryCondition(query1);
    List<String> keywords = queryCondition.getKeywords();
    Assertions.assertNotNull(keywords);
    Assertions.assertEquals(2, keywords.size());
    Assertions.assertEquals("test", keywords.get(0));
    Assertions.assertEquals("hello", keywords.get(1));
    Condition condition = queryCondition.getCondition();
    Assertions.assertInstanceOf(AndCondition.class, condition);
    AndCondition andCondition = (AndCondition) condition;
    Assertions.assertEquals(andCondition.getConditions().size(), 2);
    Assertions.assertInstanceOf(TermCondition.class, andCondition.getConditions().get(0));
    TermCondition termCondition1 = (TermCondition) andCondition.getConditions().get(0);
    Assertions.assertEquals(termCondition1.getField(), "entity_type");
    Assertions.assertEquals(termCondition1.getValue(), "MODEL");
    TermCondition termCondition2 = (TermCondition) andCondition.getConditions().get(1);
    Assertions.assertEquals(termCondition2.getField(), "catalog_name");
    Assertions.assertEquals(termCondition2.getValue(), "model_catalog");

    String query2 = "test hello";
    queryCondition = buildQueryCondition(query2);
    Assertions.assertNotNull(keywords);
    Assertions.assertEquals(2, keywords.size());
    Assertions.assertEquals("test", keywords.get(0));
    Assertions.assertEquals("hello", keywords.get(1));
    Assertions.assertNotNull(queryCondition.getCondition());
    Assertions.assertTrue(queryCondition.getCondition() instanceof AndCondition);
    andCondition = (AndCondition) queryCondition.getCondition();
    Assertions.assertEquals(andCondition.getConditions().size(), 0);

    String query3 = "test hello entity_type:MODEL OR catalog_name:model_catalog nice";
    queryCondition = buildQueryCondition(query3);
    keywords = queryCondition.getKeywords();
    Assertions.assertNotNull(keywords);
    Assertions.assertEquals(3, keywords.size());
    Assertions.assertEquals("test", keywords.get(0));
    Assertions.assertEquals("hello", keywords.get(1));
    Assertions.assertEquals("nice", keywords.get(2));

    condition = queryCondition.getCondition();
    Assertions.assertInstanceOf(OrCondition.class, condition);
    OrCondition orCondition = (OrCondition) condition;
    Assertions.assertEquals(orCondition.getConditions().size(), 2);

    Assertions.assertInstanceOf(AndCondition.class, orCondition.getConditions().get(0));
    andCondition = (AndCondition) orCondition.getConditions().get(0);
    Assertions.assertEquals(andCondition.getConditions().size(), 1);
    TermCondition termCondition3 = (TermCondition) andCondition.getConditions().get(0);
    Assertions.assertEquals(termCondition3.getField(), "entity_type");
    Assertions.assertEquals(termCondition3.getValue(), "MODEL");
    Assertions.assertInstanceOf(AndCondition.class, orCondition.getConditions().get(1));
    andCondition = (AndCondition) orCondition.getConditions().get(1);
    TermCondition termCondition4 = (TermCondition) andCondition.getConditions().get(0);
    Assertions.assertEquals(termCondition4.getField(), "catalog_name");
    Assertions.assertEquals(termCondition4.getValue(), "model_catalog");

    String query4 = "entity_type:MODEL hello AND -catalog_name:model_catalog nice";
    queryCondition = buildQueryCondition(query4);
    keywords = queryCondition.getKeywords();
    Assertions.assertNotNull(keywords);
    Assertions.assertEquals(2, keywords.size());
    Assertions.assertEquals("hello", keywords.get(0));
    Assertions.assertEquals("nice", keywords.get(1));
    condition = queryCondition.getCondition();
    Assertions.assertInstanceOf(AndCondition.class, condition);
    AndCondition andCondition2 = (AndCondition) condition;
    Assertions.assertEquals(andCondition2.getConditions().size(), 2);
    TermCondition termCondition5 = (TermCondition) andCondition2.getConditions().get(0);
    Assertions.assertEquals(termCondition5.getField(), "entity_type");
    Assertions.assertEquals(termCondition5.getValue(), "MODEL");
    Assertions.assertInstanceOf(NotCondition.class, andCondition2.getConditions().get(1));
    NotCondition notCondition = (NotCondition) andCondition2.getConditions().get(1);
    Assertions.assertInstanceOf(TermCondition.class, notCondition.getCondition());
    TermCondition termCondition6 = (TermCondition) notCondition.getCondition();
    Assertions.assertEquals(termCondition6.getField(), "catalog_name");
    Assertions.assertEquals(termCondition6.getValue(), "model_catalog");

    String query5 =
        "good -entity_type:MODEL,CATALOG hello great AND -catalog_name:model_catalog nice";
    queryCondition = buildQueryCondition(query5);
    keywords = queryCondition.getKeywords();
    Assertions.assertNotNull(keywords);
    Assertions.assertEquals(4, keywords.size());
    Assertions.assertEquals("good", keywords.get(0));
    Assertions.assertEquals("hello", keywords.get(1));
    Assertions.assertEquals("great", keywords.get(2));
    Assertions.assertEquals("nice", keywords.get(3));
    condition = queryCondition.getCondition();
    Assertions.assertInstanceOf(AndCondition.class, condition);
    AndCondition andCondition3 = (AndCondition) condition;
    Assertions.assertEquals(andCondition3.getConditions().size(), 2);
    Assertions.assertInstanceOf(NotCondition.class, andCondition3.getConditions().get(0));
    NotCondition notCondition1 = (NotCondition) andCondition3.getConditions().get(0);
    Assertions.assertInstanceOf(InCondition.class, notCondition1.getCondition());
    InCondition termCondition7 = (InCondition) notCondition1.getCondition();
    Assertions.assertEquals(termCondition7.getField(), "entity_type");
    Assertions.assertEquals(2, termCondition7.getValues().size());
    Assertions.assertEquals("MODEL", termCondition7.getValues().get(0));
    Assertions.assertEquals("CATALOG", termCondition7.getValues().get(1));

    NotCondition notCondition2 = (NotCondition) andCondition3.getConditions().get(1);
    Assertions.assertInstanceOf(TermCondition.class, notCondition2.getCondition());
    TermCondition termCondition8 = (TermCondition) notCondition2.getCondition();
    Assertions.assertEquals(termCondition8.getField(), "catalog_name");
    Assertions.assertEquals(termCondition8.getValue(), "model_catalog");
  }

  @Test
  void testNoCondition() {
    String query = "just some random text without conditions";
    QueryCondition queryCondition = buildQueryCondition(query);
    List<String> keywords = queryCondition.getKeywords();
    Assertions.assertNotNull(keywords);
    Assertions.assertEquals(6, keywords.size());
    Assertions.assertEquals("just", keywords.get(0));
    Assertions.assertEquals("some", keywords.get(1));
    Assertions.assertEquals("random", keywords.get(2));
    Assertions.assertEquals("text", keywords.get(3));
    Assertions.assertEquals("without", keywords.get(4));
    Assertions.assertEquals("conditions", keywords.get(5));

    Assertions.assertTrue(queryCondition.getCondition() instanceof AndCondition);
    AndCondition andCondition = (AndCondition) queryCondition.getCondition();
    Assertions.assertEquals(
        0, andCondition.getConditions().size(), "No conditions should be present");

    Query openSearch =
        FilterConditionUtils.convert(
            queryCondition.getCondition(), ImmutableMap.of(), ImmutableMap.of());
    Assertions.assertNotNull(openSearch);
    Assertions.assertNotNull(openSearch.toString());

    queryCondition = buildQueryCondition("");
    keywords = queryCondition.getKeywords();
    Assertions.assertNotNull(keywords);
    Assertions.assertEquals(0, keywords.size(), "No keywords should be present");
    Assertions.assertNull(queryCondition.getCondition());
  }
}
