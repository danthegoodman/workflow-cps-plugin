package org.jenkinsci.plugins.workflow.cps;

import com.cloudbees.groovy.cps.Block;
import com.cloudbees.groovy.cps.Env;
import com.cloudbees.groovy.cps.impl.CpsClosure;
import groovy.lang.Closure;
import org.codehaus.groovy.runtime.DefaultGroovyStaticMethods;
import org.codehaus.groovy.runtime.InvokerHelper;

import java.util.List;

/**
 * {@link CpsClosure} that intercepts the {@code sleep} call so that it gets handled via SleepStep,
 * instead of {@link DefaultGroovyStaticMethods#sleep(Object, long)} that Groovy adds to {@code Object}.
 *
 * <p>
 * We had to do this because we made a mistake of having the 'sleep' step pick the same name as
 * a method defined on {@code Object}. Granted, it is a method added by Groovy, not by JDK, but the end result
 * is still the same, and the consequence is as severe as trying to override {@code hashCode()} method
 * and use it for something completely different. We ended up doing this because when we did it, a bug masked
 * the severity of the problem. In hindsight, we should have thought twice before adding a work around
 * like {@link CpsScript#sleep(long)}.
 *
 * @author Kohsuke Kawaguchi
 */
public class CpsClosure2 extends CpsClosure {
    public CpsClosure2(Object owner, Object thisObject, List<String> parameters, Block body, Env capture) {
        super(owner, thisObject, parameters, body, capture);
    }

    /**
     * @see CpsScript#sleep(long)
     */
    public Object sleep(long arg) {
        return InvokerHelper.invokeMethod(getOwner(), "sleep", arg);
    }

/* Overriding methods defined in DefaultGroovyMethods
        if we don't do this, definitions in DefaultGroovyMethods get called. One problem
        is that most of them are not whitelisted, and the other problem is that they don't
        always forward the call to the closure owner.

        In CpsScript we override these methods and redefine them as variants of the 'echo' step,
        so for this to work the same from closure body, we need to redefine them.
 */
    public void println(Object arg) {
        InvokerHelper.invokeMethod(getOwner(), "println", new Object[]{arg});
    }

    public void println() {
        InvokerHelper.invokeMethod(getOwner(), "println", new Object[0]);
    }

    public void print(Object arg) {
        InvokerHelper.invokeMethod(getOwner(), "print", new Object[]{arg});
    }

    public void printf(String format, Object value) {
        InvokerHelper.invokeMethod(getOwner(), "printf", new Object[]{format,value});
    }
}