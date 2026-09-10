grammar SearchExpression;

query: expression? EOF;

expression
    : andExpression (OR andExpression)*
    ;

andExpression
    : term (AND? term)*
    ;

term
    : MINUS factor
    | literal
    | LPAREN expression RPAREN
    ;

literal
    : atom
    | factor
    ;

factor
    : ID COLON value
    ;

value
    : atom (COMMA atom)*
    ;


atom
    : ID
    | MINUS ID
    | STRING
    ;

OR      : O R;
AND     : A N D;

fragment A: ('a'|'A');
fragment D: ('d'|'D');
fragment N: ('n'|'N');
fragment O: ('o'|'O');
fragment R: ('r'|'R');

fragment WORD_CHAR: [a-zA-Z0-9_] | [\u4e00-\u9fa5];
ID: WORD_CHAR (WORD_CHAR | '-')*;
STRING: '"' ('\\' ["\\] | ~["\\\r\n])* '"';


MINUS   : '-';
COLON   : ':';
LPAREN  : '(';
RPAREN  : ')';
COMMA   : ',';


WS: [ \t\r\n]+ -> skip;