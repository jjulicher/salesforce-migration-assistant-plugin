package org.jenkinsci.plugins.sma;

import org.eclipse.jgit.api.DiffCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.*;
import java.util.*;

import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wrapper for git interactions using jGit.
 */
public class SMAGit {
    public enum Mode {STD, INI, PRB}

    private final String SOURCEDIR = "src/";

    private Git git;
    private Repository repository;
    private List<DiffEntry> diffs;
    private String prevCommit, curCommit;
    private Pattern _excludePattern;
    private Set<String> excludedFiles = new TreeSet<String>();
    private static final Logger LOG = Logger.getLogger(SMAGit.class.getName());

    public SMAGit(String pathToWorkspace,
                  String curCommit,
                  String diffAgainst,
                  Mode smaMode) throws Exception {
        this(pathToWorkspace, curCommit, diffAgainst, null, smaMode);
    }

    /**
     * Creates an SMAGit instance
     *
     * @param pathToWorkspace
     * @param curCommit
     * @param diffAgainst
     * @param excludeRegex
     * @param smaMode
     * @throws Exception
     */
    public SMAGit(String pathToWorkspace,
                  String curCommit,
                  String diffAgainst,
                  String excludeRegex,
                  Mode smaMode) throws Exception {

        if (excludeRegex != null && !excludeRegex.isEmpty()) {
            _excludePattern = Pattern.compile(excludeRegex);
        }

        String pathToRepo = pathToWorkspace + "/.git";
        File repoDir = new File(pathToRepo);
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        repository = builder.setGitDir(repoDir).readEnvironment().build();
        git = new Git(repository);

        this.curCommit = getCurrentCommit(repository, curCommit);

        if (smaMode == Mode.PRB) {
            ObjectId branchId = repository.resolve("refs/remotes/origin/" + diffAgainst);
            RevCommit targetCommit = new RevWalk(repository).parseCommit(branchId);
            this.prevCommit = targetCommit.getName();
        } else if (smaMode == Mode.STD) {
            this.prevCommit = getPreviousCommit(diffAgainst);
        }

        if (smaMode != Mode.INI) {
            getDiffs();
        }
    }

    public Set<String> getExcludedFiles() {
        return excludedFiles;
    }

    private String getCurrentCommit(Repository repository, String curCommit) throws IOException {
        if (curCommit != null && !curCommit.isEmpty()) {
            return curCommit;
        } else {
            Ref head = repository.getRef("refs/heads/master");
            return head.getName();
        }

    }

    private String getPreviousCommit(String preCommit) throws IOException, GitAPIException {
        if (preCommit == null || preCommit.isEmpty()) {
            Iterable<RevCommit> revCommitIterable = git.log().call();
            RevWalk walk = ((RevWalk) revCommitIterable);
            walk.next(); //current commit;
            RevCommit prevCommit = walk.next(); //previous commit;
            return prevCommit.getName();
        }
        return preCommit;
    }

    /**
     * Returns all of the items that were added in the current commit.
     *
     * @return The ArrayList containing all of the additions in the current commit.
     * @throws IOException
     */
    public Map<String, byte[]> getNewMetadata() throws Exception {
        Map<String, byte[]> additions = new HashMap<String, byte[]>();

        for (DiffEntry diff : diffs) {
            if (diff.getChangeType().toString().equals("ADD")) {
                String item = SMAUtility.checkMeta(diff.getNewPath());
                if (!additions.containsKey(item)
                        && item.contains(SOURCEDIR)
                        && shouldNotBeExcluded(item)) {
                    additions.put(diff.getNewPath(), getBlob(diff.getNewPath(), curCommit));
                }
            }
        }

        return additions;
    }

    private boolean shouldNotBeExcluded(String item) {
        if(_excludePattern == null){
            return true;
        }
        Matcher matcher = _excludePattern.matcher(item);

        boolean found = matcher.find();
        if (found) {
            excludedFiles.add(item);
        }
        return !found;
    }

    /**
     * Returns all of the items that were deleted in the current commit.
     *
     * @return The ArrayList containing all of the items that were deleted in the current commit.
     */
    public Map<String, byte[]> getDeletedMetadata() throws Exception {
        Map<String, byte[]> deletions = new HashMap<String, byte[]>();

        for (DiffEntry diff : diffs) {
            if (diff.getChangeType().toString().equals("DELETE")) {
                String item = SMAUtility.checkMeta(diff.getOldPath());
                if (!deletions.containsKey(item) && item.contains(SOURCEDIR)
                        && shouldNotBeExcluded(item)) {
                    deletions.put(diff.getOldPath(), getBlob(diff.getOldPath(), prevCommit));
                }
            }
        }

        return deletions;
    }

    /**
     * Returns all of the updated changes in the current commit.
     *
     * @return The ArrayList containing the items that were modified (new paths) and added to the repository.
     * @throws IOException
     */
    public Map<String, byte[]> getUpdatedMetadata() throws Exception {
        Map<String, byte[]> modifiedMetadata = new HashMap<String, byte[]>();

        for (DiffEntry diff : diffs) {
            if (diff.getChangeType().toString().equals("MODIFY")) {
                String item = SMAUtility.checkMeta(diff.getNewPath());
                if (!modifiedMetadata.containsKey(item) && item.contains(SOURCEDIR)
                        && shouldNotBeExcluded(item)) {
                    modifiedMetadata.put(diff.getNewPath(), getBlob(diff.getNewPath(), curCommit));
                }
            }
        }
        return modifiedMetadata;
    }

    /**
     * Returns all of the modified (old paths) changes in the current commit.
     *
     * @return ArrayList containing the items that were modified (old paths).
     */
    public Map<String, byte[]> getOriginalMetadata() throws Exception {
        Map<String, byte[]> originalMetadata = new HashMap<String, byte[]>();

        for (DiffEntry diff : diffs) {
            if (diff.getChangeType().toString().equals("MODIFY")) {
                String item = SMAUtility.checkMeta(diff.getOldPath());
                if (!originalMetadata.containsKey(item) && item.contains(SOURCEDIR)) {
                    originalMetadata.put(diff.getOldPath(), getBlob(diff.getOldPath(), prevCommit));
                }
            }
        }

        return originalMetadata;
    }

    /**
     * Returns the blob information for the file at the specified path and commit
     *
     * @param repoItem
     * @param commit
     * @return
     * @throws Exception
     */
    public byte[] getBlob(String repoItem, String commit) throws Exception {
        byte[] data;

        String parentPath = repository.getDirectory().getParent();

        ObjectId commitId = repository.resolve(commit);

        ObjectReader reader = repository.newObjectReader();
        RevWalk revWalk = new RevWalk(reader);
        RevCommit revCommit = revWalk.parseCommit(commitId);
        RevTree tree = revCommit.getTree();
        TreeWalk treeWalk = TreeWalk.forPath(reader, repoItem, tree);

        if (treeWalk != null) {
            ObjectLoader loader = reader.open(treeWalk.getObjectId(0));
            if (loader.isLarge()) {
                //handle large object.
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                loader.copyTo(outputStream);
                data = outputStream.toByteArray();
            } else {
                data = loader.getBytes();
            }
        } else {
            throw new IllegalStateException("Did not find expected file '" + repoItem + "'");
        }

        reader.release();

        return data;
    }

    /**
     * Replicates ls-tree for the current commit.
     *
     * @return Map containing the full path and the data for all items in the repository.
     * @throws IOException
     */
    public Map<String, byte[]> getAllMetadata() throws Exception {
        Map<String, byte[]> contents = new HashMap<String, byte[]>();
        ObjectReader reader = repository.newObjectReader();
        ObjectId commitId = repository.resolve(curCommit);
        RevWalk revWalk = new RevWalk(reader);
        RevCommit commit = revWalk.parseCommit(commitId);
        RevTree tree = commit.getTree();
        TreeWalk treeWalk = new TreeWalk(reader);
        treeWalk.addTree(tree);
        treeWalk.setRecursive(false);

        while (treeWalk.next()) {
            if (treeWalk.isSubtree()) {
                treeWalk.enterSubtree();
            } else {
                String member = treeWalk.getPathString();
                if (member.contains(SOURCEDIR)) {
                    byte[] data = getBlob(member, curCommit);
                    contents.put(member, data);
                }
            }
        }

        reader.release();

        return contents;
    }

    /**
     * Creates an updated package.xml file and commits it to the repository
     *
     * @param workspace The workspace.
     * @param userName  The user name of the committer.
     * @param userEmail The email of the committer.
     * @param manifest  The SMAPackage representation of a package manifest
     * @return A boolean value indicating whether an update was required or not.
     * @throws Exception
     */
    public boolean updatePackageXML(String workspace,
                                    String userName,
                                    String userEmail,
                                    SMAPackage manifest) throws Exception {
        File packageXml;

        // Only need to update the manifest if we have additions or deletions
        if (!getNewMetadata().isEmpty() || !getDeletedMetadata().isEmpty()) {
            // Fine the existing package.xml file in the repository
            String packageLocation = SMAUtility.findPackage(new File(workspace));

            if (!packageLocation.isEmpty()) {
                packageXml = new File(packageLocation);
            } else {
                // We couldn't find one, so just create one.
                packageXml = new File(workspace + "/unpackaged/package.xml");
                packageXml.getParentFile().mkdirs();
                packageXml.createNewFile();
            }

            // Write the manifest to the location of the package.xml in the fs
            FileOutputStream fos = new FileOutputStream(packageXml, false);
            fos.write(manifest.getPackage().getBytes());
            fos.close();

            String path = packageXml.getPath();

            // Commit the updated package.xml file to the repository
            git.add().addFilepattern(path).call();
            git.commit().setCommitter(userName, userEmail).setMessage("Jenkins updated package.xml").call();

            return true;
        }

        return false;
    }

    public Git getRepo() {
        return git;
    }

    public String getPrevCommit() {
        return prevCommit;
    }

    public String getCurCommit() {
        return curCommit;
    }

    /**
     * Returns the diff between two commits.
     *
     * @return List that contains DiffEntry objects of the changes made between the previous and current commits.
     * @throws Exception
     */
    private void getDiffs() throws Exception {
        OutputStream out = new ByteArrayOutputStream();
        CanonicalTreeParser oldTree = getTree(prevCommit);
        CanonicalTreeParser newTree = getTree(curCommit);
        DiffCommand diff = git.diff().setOutputStream(out).setOldTree(oldTree).setNewTree(newTree);
        diffs = diff.call();
    }

    /**
     * Returns the Canonical Tree Parser representation of a commit.
     *
     * @param commit Commit in the repository.
     * @return CanonicalTreeParser representing the tree for the commit.
     * @throws IOException
     */
    private CanonicalTreeParser getTree(String commit) throws IOException {
        CanonicalTreeParser tree = new CanonicalTreeParser();
        ObjectReader reader = repository.newObjectReader();
        ObjectId head = repository.resolve(commit + "^{tree}");
        tree.reset(reader, head);
        return tree;
    }
}
