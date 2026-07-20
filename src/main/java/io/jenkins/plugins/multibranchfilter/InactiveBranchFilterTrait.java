package io.jenkins.plugins.multibranchfilter;

import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.model.TaskListener;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMSourceCriteria;
import jenkins.scm.api.mixin.ChangeRequestSCMHead;
import jenkins.scm.api.mixin.TagSCMHead;
import jenkins.scm.api.trait.SCMHeadFilter;
import jenkins.scm.api.trait.SCMSourceContext;
import jenkins.scm.api.trait.SCMSourceRequest;
import jenkins.scm.api.trait.SCMSourceTrait;
import jenkins.scm.api.trait.SCMSourceTraitDescriptor;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

public class InactiveBranchFilterTrait extends SCMSourceTrait {
    private static final Logger LOGGER = Logger.getLogger(InactiveBranchFilterTrait.class.getName());
    private static final String SPLIT_REGEX = "[\\r\\n]+";
    private static final String DEFAULT_ALLOWLIST = "master\nmain";

    private final int inactivityDays;
    private String allowlist;
    private String denylist;

    @DataBoundConstructor
    public InactiveBranchFilterTrait(int inactivityDays) {
        this.inactivityDays = Math.max(0, inactivityDays);
        this.allowlist = DEFAULT_ALLOWLIST;
        this.denylist = "";
    }

    public int getInactivityDays() {
        return inactivityDays;
    }

    public String getAllowlist() {
        return normalizeList(allowlist);
    }

    public String getDenylist() {
        return normalizeList(denylist);
    }

    @DataBoundSetter
    public void setAllowlist(@Nullable String allowlist) {
        this.allowlist = normalizeList(allowlist);
    }

    @DataBoundSetter
    public void setDenylist(@Nullable String denylist) {
        this.denylist = normalizeList(denylist);
    }

    @Override
    protected void decorateContext(SCMSourceContext<?, ?> context) {
        List<Pattern> allowlistPatterns = parsePatternList(getAllowlist());
        List<Pattern> denylistPatterns = parsePatternList(getDenylist());
        int inactivityDays = this.inactivityDays;
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(inactivityDays);
        AgeExemption ageExemptHead = new AgeExemption();

        // Apply cheap name and head-type rules before the source creates a revision probe.
        context.withFilter(new SCMHeadFilter() {
            @Override
            public boolean isExcluded(SCMSourceRequest request, SCMHead head) throws IOException, InterruptedException {
                TaskListener listener = request.listener();
                String name = head.getName();
                ageExemptHead.remove();
                if (matchesAny(name, denylistPatterns)) {
                    logDecision(listener, name, true, "deny-list");
                    return true;
                }
                if (matchesAny(name, allowlistPatterns)) {
                    ageExemptHead.set(true);
                    logDecision(listener, name, false, "allow-list");
                    return false;
                }
                if (head instanceof ChangeRequestSCMHead || head instanceof TagSCMHead) {
                    ageExemptHead.set(true);
                    logDecision(listener, name, false, "change-request-or-tag");
                    return false;
                }
                if (inactivityDays <= 0) {
                    logDecision(listener, name, false, "inactive-filter-disabled");
                    return false;
                }
                return false;
            }
        });

        if (inactivityDays > 0) {
            // Reuse the source's probe, which Git creates from its single scan-wide fetch.
            context.withCriteria(new InactivityCriteria(cutoff, ageExemptHead));
        }
    }

    private static String normalizeList(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim();
    }

    private static List<Pattern> parsePatternList(String raw) {
        if (raw == null) {
            return Collections.emptyList();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Pattern> entries = new LinkedHashSet<>();
        for (String entry : trimmed.split(SPLIT_REGEX)) {
            String name = entry.trim();
            if (!name.isEmpty()) {
                try {
                    entries.add(Pattern.compile(name));
                } catch (PatternSyntaxException e) {
                    LOGGER.log(Level.WARNING, "Invalid regex in branch filter list: {0}", name);
                }
            }
        }
        return List.copyOf(entries);
    }

    private static boolean matchesAny(String name, List<Pattern> patterns) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    private static void logDecision(TaskListener listener, String name, boolean excluded, String reason) {
        listener.getLogger()
                .println("InactiveBranchFilter: " + name
                        + " decision=" + (excluded ? "exclude" : "include")
                        + " reason=" + reason);
    }

    static final class AgeExemption extends ThreadLocal<Boolean> implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    static final class InactivityCriteria implements SCMSourceCriteria {
        private static final long serialVersionUID = 1L;

        private final long cutoff;
        private final AgeExemption ageExemptHead;

        InactivityCriteria(long cutoff, AgeExemption ageExemptHead) {
            this.cutoff = cutoff;
            this.ageExemptHead = ageExemptHead;
        }

        @Override
        public boolean isHead(SCMSourceCriteria.Probe probe, TaskListener listener) throws IOException {
            String name = probe.name();
            // Preserve allow-list and pull-request/tag inclusions while consuming the one-shot marker.
            boolean ageExempt = Boolean.TRUE.equals(ageExemptHead.get());
            ageExemptHead.remove();
            if (ageExempt) {
                return true;
            }
            try {
                long lastModified = probe.lastModified();
                if (lastModified <= 0L) {
                    logDecision(listener, name, false, "last-modified-unavailable");
                    return true;
                }
                boolean excluded = lastModified < cutoff;
                logDecision(
                        listener,
                        name,
                        excluded,
                        "age-check lastModified=" + new Date(lastModified) + " cutoff=" + new Date(cutoff));
                return !excluded;
            } catch (UnsupportedOperationException e) {
                LOGGER.log(Level.FINE, "SCM probe does not support lastModified for {0}", name);
                logDecision(listener, name, false, "last-modified-unsupported");
                return true;
            }
        }
    }

    @Extension
    @Symbol("inactiveBranchFilter")
    public static class DescriptorImpl extends SCMSourceTraitDescriptor {
        @Override
        public String getDisplayName() {
            return "Filter inactive branches";
        }
    }
}
