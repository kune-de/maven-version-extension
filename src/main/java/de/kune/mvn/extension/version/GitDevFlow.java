package de.kune.mvn.extension.version;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelProcessor;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.logging.console.ConsoleLogger;
import org.codehaus.plexus.util.CollectionUtils;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableSet;
import static java.util.regex.Pattern.compile;

public class GitDevFlow implements VersionExtension {

    private static final Pattern releaseBranchPattern = compile("master");

    private static final Pattern hotfixBranchPattern = compile("(?<type>hotfix|support)-(?<base>.*?)");

    private static final Set<String> minorIncrementTypes = unmodifiableSet(new HashSet<String>(Arrays.asList("feat")));

    private static final Set<String> patchIncrementTypes = unmodifiableSet(
        new HashSet<>(Arrays.asList("fix", "docs", "style", "refactor", "perf", "test", "chore")));

    private static final String UNKNOWN_SNAPSHOT = "unknown-SNAPSHOT";

    private final static Pattern releaseTagPattern = compile("refs/tags/v(?<version>\\d+\\.\\d+\\.\\d+)");

    private final static Pattern hotfixReleaseTagPattern = compile(
        "refs/tags/v(.*?\\.(support|hotfix)\\.)?(?<version>\\d+\\.\\d+\\.\\d+)");

    protected static String determineVersion(Logger logger, File gitDirectory) {
        if (gitDirectory == null || !gitDirectory.exists() || !gitDirectory.isDirectory()) {
            logger.info(
                "Working directory ("
                        + gitDirectory
                        + ") does not exist or is not a directory, falling back to "
                        + UNKNOWN_SNAPSHOT);
            return UNKNOWN_SNAPSHOT;
        }
        try {
            Repository repository = null;
            try {
                repository = new FileRepositoryBuilder().findGitDir(gitDirectory).build();
            } catch (IllegalArgumentException e) {
                logger.info(
                    "Working directory ("
                            + gitDirectory
                            + ") is not a GIT repository, falling back to "
                            + UNKNOWN_SNAPSHOT);
                return UNKNOWN_SNAPSHOT;
            }
            logger.info("Working directory (" + gitDirectory + ") is a GIT repository");
            String branch = repository.getBranch();
            if (releaseBranchPattern.matcher(branch.toLowerCase()).matches()) {
                return determineReleaseVersion(logger, repository, branch);
            } else if (hotfixBranchPattern.matcher(branch.toLowerCase()).matches()) {
                return determineHotfixVersion(logger, repository, branch);
            } else {
                logger.info(
                    "Current branch (" + branch + ") is not a release branch, falling back to " + branch + "-SNAPSHOT");
                return branch + "-SNAPSHOT";
            }
        } catch (IOException e) {
            logger.warn(e.getClass().getSimpleName() + " caught, falling back to " + UNKNOWN_SNAPSHOT, e);
        } catch (IllegalArgumentException e) {
            logger.warn(e.getClass().getSimpleName() + " caught, falling back to " + UNKNOWN_SNAPSHOT, e);
        }
        return UNKNOWN_SNAPSHOT;
    }

    private static String determineHotfixVersion(Logger logger, Repository repository, String branch)
            throws IOException {
        logger.info("Determining version based on hotfix or support branch (" + branch + ")");
        SemVer newVer = determineVersion(logger, repository, true);
        Matcher matcher = hotfixBranchPattern.matcher(branch);
        matcher.matches();
        String versionString = matcher.group("base") + "." + matcher.group("type") + "." + newVer.getVersion();
        logger.info("Determined version: " + versionString);
        return versionString;
    }

    private static String determineReleaseVersion(Logger logger, Repository repository, String branch)
            throws IOException {
        logger.info("Determining version based on release branch (" + branch + ")");
        SemVer newVer = determineVersion(logger, repository, false);
        logger.info("Determined version: " + newVer.getVersion());
        return newVer.getVersion();
    }

    private static SemVer determineVersion(Logger logger, Repository repository, boolean includeHotfix)
            throws IOException {
        List<Ref> tags = getTags(repository);
        logger.info("Head refs: " + repository.getAllRefs().get("HEAD"));
        return determineVersion(
            logger,
            directCommitsAfterReleaseTag(logger, repository, tags, includeHotfix),
            latestReachableReleaseTag(logger, repository, tags, includeHotfix));
    }

    private static List<Ref> getTags(Repository repository) {
        return repository.getTags().entrySet().stream().map(Map.Entry::getValue).map(repository::peel).collect(
            Collectors.toList());
    }

    private static SemVer determineVersion(Logger logger, List<String> commitMessagesAfterRelease, SemVer baseRelease) {
        Set<String> commitTypes = extractTypes(commitMessagesAfterRelease);
        SemVer newVer = baseRelease == null ? SemVer.initial() : baseRelease;
        if (CollectionUtils.intersection(commitTypes, minorIncrementTypes).size() > 0) {
            logger.info("Found minor increment type(s)");
            newVer = newVer.incrementMinor(1);
        } else if (CollectionUtils.intersection(commitTypes, patchIncrementTypes).size() > 0) {
            logger.info("Found patch increment type(s)");
            newVer = newVer.incrementPatch(1);
        } else {
            logger.info("No increment type(s) found");
        }
        logger.debug("Base version bump: " + baseRelease + " -> " + newVer.getVersion());
        return newVer;
    }

    private static Set<String> extractTypes(List<String> commitMessagesAfterRelease) {
        return commitMessagesAfterRelease.stream()
                .map(m -> m.split(":")[0])
                .map(m -> m.replaceAll("\\(.*\\)", ""))
                .map(m -> m.toLowerCase())
                .collect(Collectors.toSet());
    }

    public static void main(String[] args) throws IOException, GitAPIException {
        Logger logger = new ConsoleLogger();
        logger.setThreshold(Logger.LEVEL_DEBUG);
        System.out.println(determineVersion(logger, new File("/Users/alexander/")));
        System.out.println(determineVersion(logger, new File("/Users/alexander/Development/git-branches-test")));
        System.out.println(
            determineVersion(
                logger,
                new File(
                        "/Users/alexander/Documents/Business/Deposit-Solutions/Workspaces/Deposit-Solutions/ds-comonea-compliance-reporting")));
    }

    private static List<String> directCommitsAfterReleaseTag(
            Logger logger,
            Repository repository,
            List<Ref> tags,
            boolean includeHotFix)
            throws IOException {
        logger.debug("Direct commits (1st parents): ");
        RevWalk revWalk = new RevWalk(repository);
        RevCommit head = revWalk.parseCommit(repository.findRef(Constants.HEAD).getObjectId());
        RevCommit r = head;
        List<String> result = new ArrayList<>();
        while (r != null) {
            final RevCommit q = r;
            List<Ref> revTags = tags.stream()
                    .filter(t -> t.getObjectId().equals(q.getId()) || q.getId().equals(t.getPeeledObjectId()))
                    .collect(Collectors.toList());
            logger.debug(
                "  "
                        + r.getId().getName()
                        + " "
                        + r.getShortMessage()
                        + " (parents: "
                        + r.getParentCount()
                        + ") tags="
                        + revTags);
            Pattern pattern = includeHotFix ? hotfixReleaseTagPattern : releaseTagPattern;
            if (revTags.stream().filter(t -> pattern.matcher(t.getName()).matches()).count() > 0) {
                logger.debug("Stopping at tag(s) " + revTags);
                break;
            }
            result.add(r.getShortMessage());
            if (r.getParentCount() > 0) {
                r = revWalk.parseCommit(r.getParent(0));
            } else {
                r = null;
            }
        }
        return result;
    }

    private static SemVer latestReachableReleaseTag(
            Logger logger,
            Repository repository,
            List<Ref> tags,
            boolean includeHotFix)
            throws IOException {
        logger.debug("All commits (all parents): ");
        RevWalk revWalk = new RevWalk(repository);
        RevCommit head = revWalk.parseCommit(repository.findRef(Constants.HEAD).getObjectId());
        List<RevCommit> r = asList(head);
        while (!r.isEmpty()) {
            List<RevCommit> nextParents = new ArrayList<>();
            for (final RevCommit q : r) {
                List<Ref> revTags = tags.stream()
                        .filter(t -> t.getObjectId().equals(q.getId()) || q.getId().equals(t.getPeeledObjectId()))
                        .collect(Collectors.toList());
                logger.debug(
                    "  "
                            + q.getId().getName()
                            + " "
                            + q.getShortMessage()
                            + " parents: "
                            + q.getParentCount()
                            + " "
                            + revTags);
                Pattern pattern = includeHotFix ? hotfixReleaseTagPattern : releaseTagPattern;
                if (revTags.stream().filter(t -> pattern.matcher(t.getName()).matches()).count() > 0) {
                    logger.debug("Stopping at tag(s) " + revTags);
                    Matcher matcher = pattern.matcher(revTags.get(0).getName());
                    matcher.matches();
                    return SemVer.of(matcher.group("version"));
                }
                nextParents.addAll(asList(q.getParents()).stream().map(x -> {
                    try {
                        return revWalk.parseCommit(x.getId());
                    } catch (IOException e) {
                        e.printStackTrace();
                        return null;
                    }
                }).collect(Collectors.toList()));
            }
            r = nextParents;
        }
        return null;
    }

    @Override
    public String determineVersion(
            Logger logger,
            Model model,
            Optional<MavenSession> mavenSession,
            Map<String, ?> options) {
        Object source = options.get(ModelProcessor.SOURCE);
        if (source instanceof FileModelSource) {
            File pomFile = ((FileModelSource) source).getFile();
            if (pomFile != null && pomFile.isFile() && pomFile.getName().toLowerCase().endsWith(".xml")) {
                logger.info("Enhancing " + pomFile + " version with git-dev-flow");
                return determineVersion(logger, pomFile.getParentFile());
            }
        }
        return UNKNOWN_SNAPSHOT;
    }

    private static class SemVer {

        public static SemVer of(int major, int minor, int patch) {
            return new SemVer(major, minor, patch);
        }

        public static SemVer initial() {
            return of(0, 0, 0);
        }

        private static Pattern versionStringPattern = compile("v?(?<major>\\d+?)\\.(?<minor>\\d+?)\\.(?<patch>\\d+?)");

        public static SemVer of(String versionString) {
            Matcher matcher = versionStringPattern.matcher(versionString);
            if (matcher.matches()) {
                return of(
                    Integer.parseInt(matcher.group("major")),
                    Integer.parseInt(matcher.group("minor")),
                    Integer.parseInt(matcher.group("patch")));
            }
            throw new IllegalArgumentException(versionString + " does not match " + versionStringPattern);
        }

        private final int major, minor, patch;

        private SemVer(int major, int minor, int patch) {
            this.major = major;
            this.minor = minor;
            this.patch = patch;
        }

        public SemVer incrementPatch(int increment) {
            return of(major, minor, patch + increment);
        }

        public SemVer incrementMinor(int increment) {
            return of(major, minor + increment, 0);
        }

        public SemVer incrementMajor(int increment) {
            return of(major + increment, 0, 0);
        }

        public String toString() {
            return getTag();
        }

        public String getVersion() {
            return major + "." + minor + "." + patch;
        }

        public String getTag() {
            return "v" + getVersion();
        }
    }
}
