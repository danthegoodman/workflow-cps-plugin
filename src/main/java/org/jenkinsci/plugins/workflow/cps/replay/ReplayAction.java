/*
 * The MIT License
 *
 * Copyright 2016 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.cps.replay;

import com.cloudbees.diff.Diff;
import com.google.common.collect.ImmutableList;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.Functions;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.queue.QueueTaskFuture;
import hudson.security.Permission;
import hudson.security.PermissionScope;
import hudson.util.FormValidation;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.model.TransientActionFactory;
import jenkins.scm.api.SCMRevisionAction;
import net.sf.json.JSON;
import net.sf.json.JSONObject;
import org.acegisecurity.AccessDeniedException;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Attached to a {@link Run} when it could be replayed with script edits.
 */
@SuppressWarnings("rawtypes") // on Run
public class ReplayAction implements Action {

    private final Run run;

    private ReplayAction(Run run) {
        this.run = run;
    }

    @Override public String getDisplayName() {
        return "Replay";
    }

    @Override public String getIconFileName() {
        return isEnabled() ? "redo.png" : null;
    }

    @Override public String getUrlName() {
        return isEnabled() ? "replay" : null;
    }

    private @CheckForNull CpsFlowExecution getExecution() {
        FlowExecutionOwner owner = ((FlowExecutionOwner.Executable) run).asFlowExecutionOwner();
        if (owner == null) {
            return null;
        }
        FlowExecution exec = owner.getOrNull();
        return exec instanceof CpsFlowExecution ? (CpsFlowExecution) exec : null;
    }

    /* accessible to Jelly */ public boolean isEnabled() {
        if (!run.hasPermission(REPLAY)) {
            return false;
        }
        CpsFlowExecution exec = getExecution();
        if (exec == null) {
            return false;
        }
        if (exec.isSandbox()) {
            return true;
        } else {
            // Whole-script approval mode. Can we submit an arbitrary script right here?
            return Jenkins.getActiveInstance().hasPermission(Jenkins.RUN_SCRIPTS);
        }
    }

    /** @see CpsFlowExecution#getScript */
    /* accessible to Jelly */ public String getOriginalScript() {
        CpsFlowExecution execution = getExecution();
        return execution != null ? execution.getScript() : "???";
    }

    /** @see CpsFlowExecution#getLoadedScripts */
    /* accessible to Jelly */ public Map<String,String> getOriginalLoadedScripts() {
        CpsFlowExecution execution = getExecution();
        return execution != null ? execution.getLoadedScripts() : /* ? */Collections.<String,String>emptyMap();
    }

    /* accessible to Jelly */ public Run getOwner() {
        return run;
    }

    @Restricted(DoNotUse.class)
    @RequirePOST
    public void doRun(StaplerRequest req, StaplerResponse rsp) throws ServletException, IOException {
        if (!isEnabled()) {
            throw new AccessDeniedException("not allowed to replay"); // AccessDeniedException2 requires us to look up the specific Permission
        }
        JSONObject form = req.getSubmittedForm();
        // Copy originalLoadedScripts, replacing values with those from the form wherever defined.
        Map<String,String> replacementLoadedScripts = new HashMap<String,String>();
        for (Map.Entry<String,String> entry : getOriginalLoadedScripts().entrySet()) {
            // optString since you might be replaying a running build, which might have loaded a script after the page load but before submission.
            replacementLoadedScripts.put(entry.getKey(), form.optString(entry.getKey(), entry.getValue()));
        }
        run(form.getString("mainScript"), replacementLoadedScripts);
        rsp.sendRedirect("../.."); // back to WorkflowJob; new build might not start instantly so cannot redirect to it
    }

    private static final Iterable<Class<? extends Action>> COPIED_ACTIONS = ImmutableList.of(
        ParametersAction.class,
        SCMRevisionAction.class
    );

    /**
     * For whitebox testing.
     * @param replacementMainScript main script; replacement for {@link #getOriginalScript}
     * @param replacementLoadedScripts auxiliary scripts, keyed by class name; replacement for {@link #getOriginalLoadedScripts}
     * @return a way to wait for the replayed build to complete
     */
    public @CheckForNull QueueTaskFuture/*<Run>*/ run(@Nonnull String replacementMainScript, @Nonnull Map<String,String> replacementLoadedScripts) {
        Queue.Item item = run2(replacementMainScript, replacementLoadedScripts);
        return item == null ? null : item.getFuture();
    }

    /**
     * For use in projects that want initiate a replay via the Java API.
     *
     * @param replacementMainScript main script; replacement for {@link #getOriginalScript}
     * @param replacementLoadedScripts auxiliary scripts, keyed by class name; replacement for {@link #getOriginalLoadedScripts}
     * @return build queue item
     */
    public @CheckForNull Queue.Item run2(@Nonnull String replacementMainScript, @Nonnull Map<String,String> replacementLoadedScripts) {
        List<Action> actions = new ArrayList<Action>();
        CpsFlowExecution execution = getExecution();
        if (execution == null) {
            return null;
        }
        actions.add(new ReplayFlowFactoryAction(replacementMainScript, replacementLoadedScripts, execution.isSandbox()));
        actions.add(new CauseAction(new Cause.UserIdCause(), new ReplayCause(run)));
        for (Class<? extends Action> c : COPIED_ACTIONS) {
            actions.addAll(run.getActions(c));
        }
        return ParameterizedJobMixIn.scheduleBuild2(run.getParent(), 0, actions.toArray(new Action[actions.size()]));
    }

    public String getDiff() {
        Run<?,?> original = run;
        ReplayCause cause;
        while ((cause = original.getCause(ReplayCause.class)) != null) {
            Run<?,?> earlier = cause.getOriginal();
            if (earlier == null) {
                // Deleted? Oh well.
                break;
            }
            original = earlier;
        }
        ReplayAction originalAction = original.getAction(ReplayAction.class);
        if (originalAction == null) {
            return "???";
        }
        try {
            StringBuilder diff = new StringBuilder(diff(/* TODO JENKINS-31838 */"Jenkinsfile", originalAction.getOriginalScript(), getOriginalScript()));
            Map<String,String> originalLoadedScripts = originalAction.getOriginalLoadedScripts();
            for (Map.Entry<String,String> entry : getOriginalLoadedScripts().entrySet()) {
                String script = entry.getKey();
                String originalScript = originalLoadedScripts.get(script);
                if (originalScript != null) {
                    diff.append(diff(script, originalScript, entry.getValue()));
                }
            }
            return diff.toString();
        } catch (IOException x) {
            return Functions.printThrowable(x);
        }
    }
    private static String diff(String script, String oldText, String nueText) throws IOException {
        Diff hunks = Diff.diff(new StringReader(oldText), new StringReader(nueText), false);
        // TODO rather than old vs. new could use (e.g.) build-10 vs. build-13
        return hunks.isEmpty() ? "" : hunks.toUnifiedDiff("old/" + script, "new/" + script, new StringReader(oldText), new StringReader(nueText), 3);
    }

    // Stub, we do not need to do anything here.
    public FormValidation doCheckScript() {
        return FormValidation.ok();
    }

    public JSON doCheckScriptCompile(@QueryParameter String value) {
        return Jenkins.getActiveInstance().getDescriptorByType(CpsFlowDefinition.DescriptorImpl.class).doCheckScriptCompile(value);
    }

    public static final Permission REPLAY = new Permission(Run.PERMISSIONS, "Replay", Messages._Replay_permission_description(), Item.CONFIGURE, PermissionScope.RUN);

    @SuppressFBWarnings(value="RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT", justification="getEnabled return value discarded")
    @Initializer(after=InitMilestone.PLUGINS_STARTED, before=InitMilestone.EXTENSIONS_AUGMENTED)
    public static void ensurePermissionRegistered() {
        REPLAY.getEnabled();
    }

    @Extension public static class Factory extends TransientActionFactory<Run> {

        @Override public Class<Run> type() {
            return Run.class;
        }

        @Override public Collection<? extends Action> createFor(Run run) {
            return run instanceof FlowExecutionOwner.Executable && run.getParent() instanceof ParameterizedJobMixIn.ParameterizedJob ? Collections.<Action>singleton(new ReplayAction(run)) : Collections.<Action>emptySet();
        }

    }

}
