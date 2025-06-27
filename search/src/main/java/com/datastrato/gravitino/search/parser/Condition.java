/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.parser;

import java.util.List;
import lombok.Getter;

public interface Condition {

  class TermCondition implements Condition {
    private final String field;
    private final String value;

    public TermCondition(String field, String value) {
      this.field = field;
      this.value = value;
    }

    public String getField() {
      return field;
    }

    public String getValue() {
      return value;
    }
  }

  class PrefixCondition implements Condition {
    private final String field;
    private final String value;

    public PrefixCondition(String field, String value) {
      this.field = field;
      this.value = value;
    }

    public String getField() {
      return field;
    }

    public String getValue() {
      return value;
    }
  }

  class InCondition implements Condition {
    private final String field;
    private final List<String> values;

    public InCondition(String field, List<String> values) {
      this.field = field;
      this.values = values;
    }

    public String getField() {
      return field;
    }

    public List<String> getValues() {
      return values;
    }
  }

  class NotCondition implements Condition {
    private final Condition condition;

    public NotCondition(Condition condition) {
      this.condition = condition;
    }

    public Condition getCondition() {
      return condition;
    }
  }

  class AndCondition implements Condition {
    private final List<Condition> conditions;

    public AndCondition(List<Condition> conditions) {
      this.conditions = conditions;
    }

    public List<Condition> getConditions() {
      return conditions;
    }
  }

  class OrCondition implements Condition {
    private final List<Condition> conditions;

    public OrCondition(List<Condition> conditions) {
      this.conditions = conditions;
    }

    public List<Condition> getConditions() {
      return conditions;
    }
  }

  enum RangeType {
    GREATER,
    GRATER_EQUAL,
    LESS,
    LESS_EQUAL,
  }

  @Getter
  class RangeCondition implements Condition {
    private final String field;
    private final RangeType rangeType;
    private final String value;

    public RangeCondition(String field, String value, RangeType rangeType) {
      this.field = field;
      this.value = value;
      this.rangeType = rangeType;
    }
  }

  static RangeCondition greater(String field, String value) {
    return new RangeCondition(field, value, RangeType.GREATER);
  }

  static RangeCondition greaterEqual(String field, String value) {
    return new RangeCondition(field, value, RangeType.GRATER_EQUAL);
  }

  static RangeCondition less(String field, String value) {
    return new RangeCondition(field, value, RangeType.LESS);
  }

  static RangeCondition lessEqual(String field, String value) {
    return new RangeCondition(field, value, RangeType.LESS_EQUAL);
  }
}
