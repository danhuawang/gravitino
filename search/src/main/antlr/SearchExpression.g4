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
    : ID
    | factor
    ;

factor
    : ID COLON value
    ;

value
    : ID (COMMA ID)*
    ;


OR      : O R;
AND     : A N D;

fragment A: ('a'|'A');
fragment D: ('d'|'D');
fragment N: ('n'|'N');
fragment O: ('o'|'O');
fragment R: ('r'|'R');

ID: ( [a-zA-Z0-9_] | [\u4e00-\u9fa5] )+ ;


MINUS   : '-';
COLON   : ':';
LPAREN  : '(';
RPAREN  : ')';
COMMA   : ',';


WS: [ \t\r\n]+ -> skip;