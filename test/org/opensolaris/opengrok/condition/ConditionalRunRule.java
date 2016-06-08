package org.opensolaris.opengrok.condition;

import org.junit.Assume;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.lang.reflect.Modifier;

/**
 * Cobbled together from:
 * http://www.codeaffine.com/2013/11/18/a-junit-rule-to-conditionally-ignore-tests/
 * https://gist.github.com/yinzara/9980184
 * http://cwd.dhemery.com/2010/12/junit-rules/
 * http://stackoverflow.com/questions/28145735/androidjunit4-class-org-junit-assume-assumetrue-assumptionviolatedexception/
 */

public class ConditionalRunRule implements TestRule {

    @Override
    public Statement apply(Statement aStatement, Description aDescription) {
        if (hasConditionalIgnoreAnnotationOnMethod(aDescription)) {
            RunCondition condition = getIgnoreConditionOnMethod(aDescription);
            if (!condition.isSatisfied()) {
                return new IgnoreStatement(condition);
            }
        }

        if (hasConditionalIgnoreAnnotationOnClass(aDescription)) {
            RunCondition condition = getIgnoreConditionOnClass(aDescription);
            if (!condition.isSatisfied()) {
                return new IgnoreStatement(condition);
            }
        }

        return aStatement;
    }

    private static boolean hasConditionalIgnoreAnnotationOnClass( Description aDescription ) {
        return aDescription.getTestClass().getAnnotation(ConditionalRun.class) != null;
    }

    private static RunCondition getIgnoreConditionOnClass( Description aDescription ) {
        ConditionalRun annotation = aDescription.getTestClass().getAnnotation(ConditionalRun.class);
        return new IgnoreConditionCreator(aDescription.getTestClass(), annotation ).create();
    }

    private static boolean hasConditionalIgnoreAnnotationOnMethod( Description aDescription ) {
        return aDescription.getAnnotation(ConditionalRun.class) != null;
    }

    private static RunCondition getIgnoreConditionOnMethod( Description aDescription ) {
        ConditionalRun annotation = aDescription.getAnnotation(ConditionalRun.class);
        return new IgnoreConditionCreator(aDescription.getTestClass(), annotation ).create();
    }

    private static class IgnoreConditionCreator {
        private final Class<?> mTestClass;
        private final Class<? extends RunCondition> conditionType;

        IgnoreConditionCreator(Class<?> aTestClass, ConditionalRun annotation) {
            this.mTestClass = aTestClass;
            this.conditionType = annotation.condition();
        }

        RunCondition create() {
            checkConditionType();
            try {
                return createCondition();
            } catch( RuntimeException re ) {
                throw re;
            } catch( Exception e ) {
                throw new RuntimeException( e );
            }
        }

        private RunCondition createCondition() throws Exception {
            RunCondition result;
            if(isConditionTypeStandalone()) {
                result = conditionType.newInstance();
            } else {
                result = conditionType.getDeclaredConstructor(mTestClass).newInstance(mTestClass);
            }
            return result;
        }

        private void checkConditionType() {
            if(!isConditionTypeStandalone() && !isConditionTypeDeclaredInTarget()) {
                String msg
                        = "Conditional class '%s' is a member class "
                        + "but was not declared inside the test case using it.\n"
                        + "Either make this class a static class, "
                        + "standalone class (by declaring it in it's own file) "
                        + "or move it inside the test case using it";
                throw new IllegalArgumentException( String.format (msg, conditionType.getName()) );
            }
        }

        private boolean isConditionTypeStandalone() {
            return !conditionType.isMemberClass()
                    || Modifier.isStatic(conditionType.getModifiers());
        }

        private boolean isConditionTypeDeclaredInTarget() {
            return mTestClass.getClass().isAssignableFrom(conditionType.getDeclaringClass());
        }
    }

    private static class IgnoreStatement extends Statement {
        private RunCondition condition;

        IgnoreStatement(RunCondition condition) {
            this.condition = condition;
        }

        @Override
        public void evaluate() {
            Assume.assumeTrue("Ignored by " + condition.getClass().getSimpleName(), false );
        }
    }
}