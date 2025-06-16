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
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestQueryParser {

  @Test
  public void testParser() {
    String query = "field1:value1 AND field2:value2 OR field3:value3";
    Condition condition = QueryParser.parse(query);
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
    condition = QueryParser.parse(query);
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
    condition = QueryParser.parse(query);
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
    condition = QueryParser.parse(query);
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
    Condition condition = QueryParser.parse(query);
    Assertions.assertInstanceOf(NotCondition.class, condition);
    NotCondition notCondition = (NotCondition) condition;
    Assertions.assertInstanceOf(TermCondition.class, notCondition.getCondition());

    query = "-field1:value1 AND -field2:value2 OR -field3:value3";
    condition = QueryParser.parse(query);
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
    Condition condition = QueryParser.parse(query);
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
  void testQueryParser() {
    String query1 = "test entity_type:MODEL hello AND catalog_name:model_catalog";
    Pair<String, String> keywordAndFilter1 = QueryParser.parserQuery(query1);
    String keywords1 = keywordAndFilter1.getLeft();
    String filter1 = keywordAndFilter1.getRight();
    Assertions.assertEquals("test hello", keywords1);
    Assertions.assertEquals("entity_type:MODEL AND catalog_name:model_catalog", filter1);

    String query2 = "test hello";
    Pair<String, String> keywordAndFilter2 = QueryParser.parserQuery(query2);
    String keywords2 = keywordAndFilter2.getLeft();
    String filter2 = keywordAndFilter2.getRight();
    Assertions.assertEquals("test hello", keywords2);
    Assertions.assertEquals("", filter2);

    String query3 = "test hello entity_type:MODEL OR catalog_name:model_catalog nice";
    Pair<String, String> keywordAndFilter3 = QueryParser.parserQuery(query3);
    String keywords3 = keywordAndFilter3.getLeft();
    String filter3 = keywordAndFilter3.getRight();
    Assertions.assertEquals("test hello nice", keywords3);
    Assertions.assertEquals("entity_type:MODEL OR catalog_name:model_catalog", filter3);

    String query4 = "entity_type:MODEL hello AND -catalog_name:model_catalog nice";
    Pair<String, String> keywordAndFilter4 = QueryParser.parserQuery(query4);
    String keywords4 = keywordAndFilter4.getLeft();
    String filter4 = keywordAndFilter4.getRight();
    Assertions.assertEquals("hello nice", keywords4);
    Assertions.assertEquals("entity_type:MODEL AND -catalog_name:model_catalog", filter4);

    String query5 =
        "good -entity_type:MODEL,CATALOG hello great AND -catalog_name:model_catalog nice";
    Pair<String, String> keywordAndFilter5 = QueryParser.parserQuery(query5);
    String keywords5 = keywordAndFilter5.getLeft();
    String filter5 = keywordAndFilter5.getRight();
    Assertions.assertEquals("good hello great nice", keywords5);
    Assertions.assertEquals("-entity_type:MODEL,CATALOG AND -catalog_name:model_catalog", filter5);
  }

  @Test
  void testNoAnd() {
    String query = "field1:value1 field2:value2";
    Condition condition = QueryParser.parse(query);
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
    condition = QueryParser.parse(query);
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
    condition = QueryParser.parse(query);
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
}
