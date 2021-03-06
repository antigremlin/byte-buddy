package net.bytebuddy.matcher;


import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.test.utility.MockitoRule;
import net.bytebuddy.test.utility.ObjectPropertyAssertion;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.mockito.Mock;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;

public class LatentMatcherForFieldTokenTest {

    @Rule
    public TestRule mockitoRule = new MockitoRule(this);

    @Mock
    private FieldDescription.Token token, otherToken;

    @Mock
    private TypeDescription instrumentedType;

    @Mock
    private FieldDescription fieldDescription;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        when(token.accept(any(TypeDescription.Generic.Visitor.class))).thenReturn(token);
    }

    @Test
    public void testMatch() throws Exception {
        when(fieldDescription.asToken(ElementMatchers.is(instrumentedType))).thenReturn(token);
        assertThat(new LatentMatcher.ForFieldToken(token).resolve(instrumentedType).matches(fieldDescription), is(true));
    }

    @Test
    public void testNoMatch() throws Exception {
        when(fieldDescription.asToken(ElementMatchers.is(instrumentedType))).thenReturn(otherToken);
        assertThat(new LatentMatcher.ForFieldToken(token).resolve(instrumentedType).matches(fieldDescription), is(false));
    }

    @Test
    public void testObjectProperties() throws Exception {
        ObjectPropertyAssertion.of(LatentMatcher.ForFieldToken.class).apply();
        ObjectPropertyAssertion.of(LatentMatcher.ForFieldToken.ResolvedMatcher.class).apply();
    }
}
