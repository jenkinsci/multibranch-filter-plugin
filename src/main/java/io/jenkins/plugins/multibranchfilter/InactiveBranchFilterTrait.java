package io.jenkins.plugins.multibranchfilter;

import hudson.Extension;
import hudson.model.TaskListener;
import java.io.IOException;
import java.lang.reflect.Field;
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
import jenkins.scm.api.SCMFileSystem;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.mixin.ChangeRequestSCMHead;
import jenkins.scm.api.mixin.TagSCMHead;
import jenkins.scm.api.trait.SCMHeadFilter;
import jenkins.scm.api.trait.SCMSourceContext;
import jenkins.scm.api.trait.SCMSourceRequest;
import jenkins.scm.api.trait.SCMSourceTrait;
import jenkins.scm.api.trait.SCMSourceTraitDescriptor;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

public class InactiveBranchFilterTrait extends SCMSourceTrait {
    private static final Logger LOGGER = Logger.getLogger(InactiveBranchFilterTrait.class.getName());
    private static final String SPLIT_REGEX = "[,\\r\\n]+";
    private static final String DEFAULT_WHITELIST = "master\nmain";
    private static volatile boolean sourceFieldInitialized;
    private static volatile Field sourceField;

    private final int inactivityDays;
    private String whitelist;
    private String blacklist;

    @DataBoundConstructor
    public InactiveBranchFilterTrait(int inactivityDays) {
        this.inactivityDays = Math.max(0, inactivityDays);
        this.whitelist = DEFAULT_WHITELIST;
        this.blacklist = "";
    }

    public int getInactivityDays() {
        return inactivityDays;
    }

    public String getWhitelist() {
        return normalizeList(whitelist);
    }

    public String getBlacklist() {
        return normalizeList(blacklist);
    }

    @DataBoundSetter
    public void setWhitelist(@Nullable String whitelist) {
        this.whitelist = normalizeList(whitelist);
    }

    @DataBoundSetter
    public void setBlacklist(@Nullable String blacklist) {
        this.blacklist = normalizeList(blacklist);
    }

    @Override
    protected void decorateContext(SCMSourceContext<?, ?> context) {
        List<Pattern> whitelistPatterns = parsePatternList(getWhitelist());
        List<Pattern> blacklistPatterns = parsePatternList(getBlacklist());
        int inactivityDays = this.inactivityDays;

        context.withFilter(new SCMHeadFilter() {
            @Override
            public boolean isExcluded(SCMSourceRequest request, SCMHead head)
                    throws IOException, InterruptedException {
                TaskListener listener = request.listener();
                String name = head.getName();
                if (matchesAny(name, blacklistPatterns)) {
                    logDecision(listener, name, true, "blacklist");
                    return true;
                }
                if (matchesAny(name, whitelistPatterns)) {
                    logDecision(listener, name, false, "whitelist");
                    return false;
                }
                if (head instanceof ChangeRequestSCMHead || head instanceof TagSCMHead) {
                    logDecision(listener, name, false, "change-request-or-tag");
                    return false;
                }
                if (inactivityDays <= 0) {
                    logDecision(listener, name, false, "inactive-filter-disabled");
                    return false;
                }

                SCMSource source = extractSource(request);
                if (source == null) {
                    logDecision(listener, name, false, "scm-source-unavailable");
                    return false;
                }

                SCMFileSystem fileSystem = SCMFileSystem.of(source, head);
                if (fileSystem == null) {
                    logDecision(listener, name, false, "scm-filesystem-unavailable");
                    return false;
                }
                try (SCMFileSystem closeable = fileSystem) {
                    long lastModified = closeable.lastModified();
                    if (lastModified <= 0L) {
                        logDecision(listener, name, false, "last-modified-unavailable");
                        return false;
                    }
                    long cutoff = System.currentTimeMillis()
                            - TimeUnit.DAYS.toMillis(inactivityDays);
                    boolean excluded = lastModified < cutoff;
                    logDecision(listener, name, excluded,
                            "age-check lastModified=" + new Date(lastModified)
                                    + " cutoff=" + new Date(cutoff));
                    return excluded;
                } catch (UnsupportedOperationException e) {
                    LOGGER.log(Level.FINE,
                            "SCMFileSystem does not support lastModified for {0}", name);
                    logDecision(listener, name, false, "last-modified-unsupported");
                    return false;
                }
            }
        });
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
                    LOGGER.log(Level.WARNING,
                            "Invalid regex in branch filter list: {0}", name);
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
        listener.getLogger().println("InactiveBranchFilter: " + name
                + " decision=" + (excluded ? "exclude" : "include")
                + " reason=" + reason);
    }

    private static SCMSource extractSource(SCMSourceRequest request) {
        Field field = getSourceField();
        if (field == null) {
            return null;
        }
        try {
            return (SCMSource) field.get(request);
        } catch (IllegalAccessException e) {
            LOGGER.log(Level.FINE, "Unable to access SCMSource from request", e);
            return null;
        }
    }

    private static Field getSourceField() {
        if (sourceFieldInitialized) {
            return sourceField;
        }
        synchronized (InactiveBranchFilterTrait.class) {
            if (!sourceFieldInitialized) {
                try {
                    // SCMSourceRequest does not expose the SCMSource; use reflection to access it.
                    Field field = SCMSourceRequest.class.getDeclaredField("source");
                    field.setAccessible(true);
                    sourceField = field;
                } catch (NoSuchFieldException | RuntimeException e) {
                    LOGGER.log(Level.FINE, "Unable to locate SCMSourceRequest source field", e);
                    sourceField = null;
                }
                sourceFieldInitialized = true;
            }
        }
        return sourceField;
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
