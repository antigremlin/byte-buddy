package net.bytebuddy.description.method;

import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.description.type.TypeVariableToken;
import net.bytebuddy.test.utility.MockitoRule;
import net.bytebuddy.test.utility.ObjectPropertyAssertion;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.mockito.Mock;

import java.util.Collections;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MethodDescriptionTokenTest {

    private static final String FOO = "foo";

    private static final int MODIFIERS = 42;

    @Rule
    public TestRule mockitoRule = new MockitoRule(this);

    @Mock
    private TypeDescription.Generic returnType, visitedReturnType, exceptionType, visitedExceptionType;

    @Mock
    private ParameterDescription.Token parameterToken, visitedParameterToken;

    @Mock
    private TypeVariableToken typeVariableToken, visitedTypeVariableToken;

    @Mock
    private AnnotationDescription annotation;

    @Mock
    private Object defaultValue;

    @Mock
    private TypeDescription.Generic.Visitor<? extends TypeDescription.Generic> visitor;

    @Before
    public void setUp() throws Exception {
        when(typeVariableToken.accept(visitor)).thenReturn(visitedTypeVariableToken);
        when(returnType.asGenericType()).thenReturn(returnType);
        when(visitedReturnType.asGenericType()).thenReturn(visitedReturnType);
        when(returnType.accept(visitor)).thenReturn(visitedReturnType);
        when(exceptionType.asGenericType()).thenReturn(exceptionType);
        when(visitedExceptionType.asGenericType()).thenReturn(visitedExceptionType);
        when(exceptionType.accept(visitor)).thenReturn(visitedExceptionType);
        when(parameterToken.accept(visitor)).thenReturn(visitedParameterToken);
    }

    @Test
    public void testProperties() throws Exception {
        MethodDescription.Token token = new MethodDescription.Token(FOO,
                MODIFIERS,
                Collections.singletonList(typeVariableToken),
                returnType,
                Collections.singletonList(parameterToken),
                Collections.singletonList(exceptionType),
                Collections.singletonList(annotation),
                defaultValue);
        assertThat(token.getName(), is(FOO));
        assertThat(token.getModifiers(), is(MODIFIERS));
        assertThat(token.getTypeVariableTokens(), is(Collections.singletonList(typeVariableToken)));
        assertThat(token.getReturnType(), is(returnType));
        assertThat(token.getParameterTokens(), is(Collections.singletonList(parameterToken)));
        assertThat(token.getExceptionTypes(), is(Collections.singletonList(exceptionType)));
        assertThat(token.getAnnotations(), is(Collections.singletonList(annotation)));
        assertThat(token.getDefaultValue(), is(defaultValue));
    }

    @Test
    public void testVisitor() throws Exception {
        assertThat(new MethodDescription.Token(FOO,
                        MODIFIERS,
                        Collections.singletonList(typeVariableToken),
                        returnType,
                        Collections.singletonList(parameterToken),
                        Collections.singletonList(exceptionType),
                        Collections.singletonList(annotation),
                        defaultValue).accept(visitor),
                is(new MethodDescription.Token(FOO,
                        MODIFIERS,
                        Collections.singletonList(visitedTypeVariableToken),
                        visitedReturnType,
                        Collections.singletonList(visitedParameterToken),
                        Collections.singletonList(visitedExceptionType),
                        Collections.singletonList(annotation),
                        defaultValue)));
    }

    @Test
    public void testObjectProperties() throws Exception {
        ObjectPropertyAssertion.of(MethodDescription.Token.class).create(new ObjectPropertyAssertion.Creator<List<?>>() {
            @Override
            public List<?> create() {
                TypeDescription.Generic typeDescription = mock(TypeDescription.Generic.class);
                when(typeDescription.asGenericType()).thenReturn(typeDescription);
                return Collections.singletonList(typeDescription);
            }
        }).apply();
        ObjectPropertyAssertion.of(MethodDescription.SignatureToken.class).apply();
        ObjectPropertyAssertion.of(MethodDescription.TypeToken.class).apply();
    }
}
