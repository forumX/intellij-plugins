// Copyright 2000-2018 JetBrains s.r.o.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package org.angular2.lang.parser;

import com.intellij.lang.PsiBuilder;
import com.intellij.lang.impl.PsiBuilderImpl;
import com.intellij.lang.javascript.*;
import com.intellij.lang.javascript.parsing.*;
import com.intellij.psi.tree.IElementType;
import org.angular2.lang.lexer.Angular2TokenTypes;

/**
 * @author Dennis.Ushakov
 */
public class Angular2Parser
  extends JavaScriptParser<Angular2Parser.Angular2ExpressionParser, StatementParser, FunctionParser, JSPsiTypeParser> {

  public Angular2Parser(PsiBuilder builder) {
    super(JavaScriptSupportLoader.JAVASCRIPT_1_5, builder);
    myExpressionParser = new Angular2ExpressionParser();
    myStatementParser = new StatementParser<Angular2Parser>(this) {
      @Override
      protected void doParseStatement(boolean canHaveClasses) {
        final IElementType firstToken = builder.getTokenType();
        if (firstToken == JSTokenTypes.LBRACE) {
          parseExpressionStatement();
          checkForSemicolon();
          return;
        }
        if (isIdentifierToken(firstToken)) {
          final IElementType nextToken = builder.lookAhead(1);
          if (nextToken == JSTokenTypes.IN_KEYWORD) {
            parseInStatement();
            return;
          }
        }
        if (tryParseNgIfStatement()) {
          return;
        }
        if (firstToken == JSTokenTypes.LET_KEYWORD) {
          if (builder.lookAhead(2) != JSTokenTypes.EQ) {
            parseNgForStatement();
            return;
          }
          parseExpressionStatement();
          return;
        }
        if (builder.getTokenType() == JSTokenTypes.LPAR) {
          if (parseInStatement()) {
            return;
          }
        }
        super.doParseStatement(canHaveClasses);
      }

      private void parseNgForStatement() {
        PsiBuilder.Marker statement = builder.mark();
        if (!getExpressionParser().parseForExpression()) {
          statement.drop();
          return;
        }
        checkForSemicolon();
        statement.done(JSElementTypes.EXPRESSION_STATEMENT);
      }

      private boolean parseInStatement() {
        PsiBuilder.Marker statement = builder.mark();
        if (!getExpressionParser().parseInExpression()) {
          statement.drop();
          return false;
        }
        statement.done(JSElementTypes.EXPRESSION_STATEMENT);
        return true;
      }

      private boolean tryParseNgIfStatement() {
        PsiBuilder.Marker ngIf = builder.mark();
        getExpressionParser().parseExpression();
        if (builder.getTokenType() != JSTokenTypes.SEMICOLON) {
          ngIf.rollbackTo();
          return false;
        }
        builder.advanceLexer();
        if (builder.getTokenType() == JSTokenTypes.LET_KEYWORD) {
          getExpressionParser().parseHashDefinition();
          builder.advanceLexer();
        }
        if (builder.getTokenType() != Angular2TokenTypes.THEN && builder.getTokenType() != JSTokenTypes.ELSE_KEYWORD) {
          ngIf.rollbackTo();
          return false;
        }

        parseBranch(Angular2TokenTypes.THEN);
        if (builder.getTokenType() == JSTokenTypes.SEMICOLON) {
          builder.advanceLexer();
        }
        parseBranch(JSTokenTypes.ELSE_KEYWORD);
        ngIf.done(JSElementTypes.IF_STATEMENT);
        checkForSemicolon();
        if (builder.getTokenType() == JSTokenTypes.LET_KEYWORD) {
          getExpressionParser().parseHashDefinition();
        }
        return true;
      }

      private void parseBranch(IElementType branchType) {
        if (builder.getTokenType() == branchType) {
          builder.advanceLexer();
          if (builder.getTokenType() == JSTokenTypes.IDENTIFIER) {
            buildTokenElement(JSElementTypes.REFERENCE_EXPRESSION);
          } else {
            builder.error(JSBundle.message("javascript.parser.message.expected.identifier"));
          }
        }
      }
    };
  }

  @Override
  public boolean isIdentifierName(IElementType firstToken) {
    return super.isIdentifierName(firstToken) || firstToken == Angular2TokenTypes.THEN;
  }

  public void parseAngular(IElementType root) {
    final PsiBuilder.Marker rootMarker = builder.mark();
    while (!builder.eof()) {
      getStatementParser().parseStatement();
    }
    rootMarker.done(root);
  }

  protected class Angular2ExpressionParser extends ExpressionParser<Angular2Parser> {
    private final Angular2MessageFormatParser myAngular2MessageFormatParser;

    public Angular2ExpressionParser() {
      super(Angular2Parser.this);
      myAngular2MessageFormatParser = new Angular2MessageFormatParser(myJavaScriptParser);
    }

    @Override
    protected boolean parseUnaryExpression() {
      final IElementType tokenType = builder.getTokenType();
      if (tokenType == JSTokenTypes.OR) {
        builder.advanceLexer();
        if (!parseFilter()) {
          builder.error("expected filter");
        }
        return true;
      }
      return super.parseUnaryExpression();
    }

    @Override
    public boolean parsePrimaryExpression() {
      final IElementType firstToken = builder.getTokenType();
      if (firstToken == JSTokenTypes.STRING_LITERAL) {
        return parseStringLiteral(firstToken);
      }
      if (firstToken == JSTokenTypes.LET_KEYWORD) {
        parseHashDefinition();
        return true;
      }
      if (isIdentifierToken(firstToken)) {
        if (myAngular2MessageFormatParser.parseMessage()) {
          return true;
        }
        int cur = -1;
        IElementType prev = builder.rawLookup(-1);
        while (prev != null && ((PsiBuilderImpl)builder).whitespaceOrComment(prev)) {
          prev = builder.rawLookup(--cur);
        }
        if (prev == JSTokenTypes.AS_KEYWORD) {
          parseExplicitIdentifierWithError();
          return true;
        }
      }
      return super.parsePrimaryExpression();
    }

    private void parseExplicitIdentifierWithError() {
      if (isIdentifierToken(builder.getTokenType())) {
        final PsiBuilder.Marker def = builder.mark();
        buildTokenElement(JSElementTypes.REFERENCE_EXPRESSION);
        def.done(JSStubElementTypes.DEFINITION_EXPRESSION);
      } else {
        builder.error(JSBundle.message("javascript.parser.message.expected.identifier"));
      }
    }

    @Override
    protected boolean isReferenceQualifierSeparator(IElementType tokenType) {
      return tokenType == Angular2TokenTypes.ELVIS ||
             //tokenType == Angular2TokenTypes.ASSERT_NOT_NULL ||
             super.isReferenceQualifierSeparator(tokenType);
    }

    @Override
    protected int getCurrentBinarySignPriority(boolean allowIn, boolean advance) {
      if (builder.getTokenType() == JSTokenTypes.OR) return 10;
      return super.getCurrentBinarySignPriority(allowIn, advance);
    }

    private boolean parseFilter() {
      final PsiBuilder.Marker mark = builder.mark();
      buildTokenElement(JSElementTypes.REFERENCE_EXPRESSION);
      PsiBuilder.Marker arguments = null;
      while (builder.getTokenType() == JSTokenTypes.COLON) {
        arguments = arguments == null ? builder.mark() : arguments;
        builder.advanceLexer();
        if (!super.parseUnaryExpression()) {
          builder.error(JSBundle.message("javascript.parser.message.expected.expression"));
        }
      }
      if (arguments != null) {
        arguments.done(JSElementTypes.ARGUMENT_LIST);
      }
      mark.done(Angular2ElementTypes.FILTER_EXPRESSION);
      return true;
    }

    private boolean parseStringLiteral(IElementType firstToken) {
      final PsiBuilder.Marker mark = builder.mark();
      IElementType currentToken = firstToken;
      StringBuilder literal = new StringBuilder();
      while (currentToken == JSTokenTypes.STRING_LITERAL ||
             currentToken == Angular2TokenTypes.ESCAPE_SEQUENCE ||
             currentToken == Angular2TokenTypes.INVALID_ESCAPE_SEQUENCE) {
        literal.append(builder.getTokenText());
        builder.advanceLexer();
        currentToken = builder.getTokenType();
      }
      mark.done(JSStubElementTypes.LITERAL_EXPRESSION);
      final String errorMessage = validateLiteralText(literal.toString());
      if (errorMessage != null) {
        builder.error(errorMessage);
      }
      return true;
    }

    public boolean parseForExpression() {
      final PsiBuilder.Marker expr = builder.mark();
      parseHashDefinition();

      if (builder.getTokenType() != JSTokenTypes.OF_KEYWORD) {
        expr.drop();
        return true;
      } else {
        builder.advanceLexer();
      }
      parseExpression();
      //if (builder.lookAhead(1) == Angular2TokenTypes.TRACK_BY_KEYWORD) {
      //  builder.advanceLexer();
      //  builder.advanceLexer();
      //  if (builder.getTokenType() != JSTokenTypes.COLON) {
      //    builder.error(JSBundle.message("javascript.parser.message.expected.colon"));
      //  } else {
      //    builder.advanceLexer();
      //  }
      //  parseExpression();
      //}
      expr.done(Angular2ElementTypes.FOR_EXPRESSION);
      return true;
    }

    protected void parseHashDefinition() {
      final PsiBuilder.Marker def = builder.mark();
      builder.advanceLexer();
      if (builder.getTokenType() != JSTokenTypes.IDENTIFIER) {
        builder.error(JSBundle.message("javascript.parser.message.expected.identifier"));
      } else {
        buildTokenElement(JSStubElementTypes.VARIABLE);
      }
      def.done(JSStubElementTypes.VAR_STATEMENT);
    }

    public boolean parseInExpression() {
      final PsiBuilder.Marker expr = builder.mark();
      if (isIdentifierToken(builder.getTokenType())) {
        PsiBuilder.Marker statement = builder.mark();
        buildTokenElement(JSStubElementTypes.VARIABLE);
        statement.done(JSStubElementTypes.VAR_STATEMENT);
      } else {
        final PsiBuilder.Marker keyValue = builder.mark();
        parseKeyValue();
        if (builder.getTokenType() != JSTokenTypes.IN_KEYWORD) {
          expr.rollbackTo();
          return false;
        } else {
          keyValue.done(JSElementTypes.PARENTHESIZED_EXPRESSION);
        }
      }
      builder.advanceLexer();
      parseExpression();
      //if (builder.getTokenType() == Angular2TokenTypes.TRACK_BY_KEYWORD) {
      //  builder.advanceLexer();
      //  parseExpression();
      //}
      expr.done(Angular2ElementTypes.REPEAT_EXPRESSION);
      return true;
    }

    private void parseKeyValue() {
      builder.advanceLexer();
      final PsiBuilder.Marker comma = builder.mark();
      if (isIdentifierToken(builder.getTokenType())) {
        buildTokenElement(JSStubElementTypes.VARIABLE);
      } else {
        builder.error(JSBundle.message("javascript.parser.message.expected.identifier"));
      }
      if (builder.getTokenType() == JSTokenTypes.COMMA) {
        builder.advanceLexer();
      } else {
        builder.error(JSBundle.message("javascript.parser.message.expected.comma"));
      }
      if (isIdentifierToken(builder.getTokenType())) {
        buildTokenElement(JSStubElementTypes.VARIABLE);
      } else {
        builder.error(JSBundle.message("javascript.parser.message.expected.identifier"));
      }
      comma.done(JSStubElementTypes.VAR_STATEMENT);
      if (builder.getTokenType() == JSTokenTypes.RPAR) {
        builder.advanceLexer();
      } else {
        builder.error(JSBundle.message("javascript.parser.message.expected.rparen"));
      }
    }
  }
}