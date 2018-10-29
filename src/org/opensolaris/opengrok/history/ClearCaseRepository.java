/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License (the "License").
 * You may not use this file except in compliance with the License.
 *
 * See LICENSE.txt included in this distribution for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at LICENSE.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information: Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 */

/*
 * Copyright (c) 2008, 2017, Oracle and/or its affiliates. All rights reserved.
 */
package org.opensolaris.opengrok.history;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.opensolaris.opengrok.logger.LoggerFactory;
import org.opensolaris.opengrok.util.Executor;
import org.opensolaris.opengrok.util.IOUtils;

/**
 * Access to a ClearCase repository.
 *
 */
public class ClearCaseRepository extends Repository {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClearCaseRepository.class);

    private static final long serialVersionUID = 1L;
    /**
     * The property name used to obtain the client command for this repository.
     */
    public static final String CMD_PROPERTY_KEY
            = "org.opensolaris.opengrok.history.ClearCase";
    /**
     * The command to use to access the repository if none was given explicitly
     */
    public static final String CMD_FALLBACK = "cleartool";

    private boolean verbose;

    public ClearCaseRepository() {
        type = "ClearCase";
        datePatterns = new String[]{
            "yyyyMMdd.HHmmss"
        };
    }

    /**
     * Use verbose log messages, or just the summary
     *
     * @return true if verbose log messages are used for this repository
     */
    public boolean isVerbose() {
        return verbose;
    }

    /**
     * Specify if verbose log messages or just the summary should be used
     *
     * @param verbose set to true if verbose messages should be used
     */
    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    /**
     * Get an executor to be used for retrieving the history log for the named
     * file.
     *
     * @param file The file to retrieve history for
     * @return An Executor ready to be started
     */
    Executor getHistoryLogExecutor(final File file) throws IOException {
        String abs = file.getCanonicalPath();
        String filename = "";
        if (abs.length() > directoryName.length()) {
            filename = abs.substring(directoryName.length() + 1);
        }

        List<String> cmd = new ArrayList<>();
        ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
        cmd.add(RepoCommand);
        cmd.add("lshistory");
        if (file.isDirectory()) {
            cmd.add("-dir");
        }
        cmd.add("-fmt");
		//
		// Copyright 2009 to current year.
		// AVEVA Solutions Ltd and its subsidiaries. All rights reserved.
		// Modifications by Stefan Eskelid for AVEVA Solutions Ltd.
		//
		// Only add username to history, for use with SharePoint user
		// directory.
		//
        cmd.add("%e\n%Nd\n%u\n%Vn\n%Nc\n.\n");
        cmd.add(filename);

        return new Executor(cmd, new File(getDirectoryName()));
    }

    @Override
    public InputStream getHistoryGet(String parent, String basename, String rev) {
        InputStream ret = null;

        File directory = new File(directoryName);

        Process process = null;
        try {
            String filename = (new File(parent, basename)).getCanonicalPath()
                    .substring(directoryName.length() + 1);
            final File tmp = File.createTempFile("opengrok", "tmp");
            String tmpName = tmp.getCanonicalPath();

            // cleartool can't get to a previously existing file
            if (tmp.exists() && !tmp.delete()) {
                LOGGER.log(Level.WARNING,
                        "Failed to remove temporary file used by history cache");
            }

            String decorated = filename + "@@" + rev;
            ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
            String argv[] = {RepoCommand, "get", "-to", tmpName, decorated};
            process = Runtime.getRuntime().exec(argv, null, directory);

            drainStream(process.getInputStream());

            if (waitFor(process) != 0) {
                return null;
            }

            ret = new BufferedInputStream(new FileInputStream(tmp)) {

                @Override
                public void close() throws IOException {
                    super.close();
                    // delete the temporary file on close
                    if (!tmp.delete()) {
                        // failed, lets do the next best thing then ..
                        // delete it on JVM exit
                        tmp.deleteOnExit();
                    }
                }
            };
        } catch (Exception exp) {
            LOGGER.log(Level.SEVERE,
                    "Failed to get history: " + exp.getClass().toString(), exp);
        } finally {
            // Clean up zombie-processes...
            if (process != null) {
                try {
                    process.exitValue();
                } catch (IllegalThreadStateException exp) {
                    // the process is still running??? just kill it..
                    process.destroy();
                }
            }
        }

        return ret;
    }

    /**
     * Drain all data from a stream and close it.
     *
     * @param in the stream to drain
     * @throws IOException if an I/O error occurs
     */
	private static void drainStream(InputStream in) throws IOException {
		//
    	// Copyright 2009 to current year.
    	// AVEVA Solutions Ltd and its subsidiaries. All rights reserved.
		// Modifications by Stefan Eskelid for AVEVA Solutions Ltd.
    	//
		// Clearcase file revisions from history are not served in Windows
		// (Bugzilla #19038) #519
		// https://github.com/OpenGrok/OpenGrok/issues/519
		//
		while (true) {
			try {
				int to_skip = in.available();
				in.skip(to_skip);
			} catch (IOException ioe) {
				// ignored - stream isn't seekable, but skipped variable still
				// has correct value.
				LOGGER.log(Level.FINEST, "Stream not seekable", ioe);
			}
			if (in.read() == -1) {
				// No bytes skipped, checked that we've reached EOF with read()
				break;
			}
		}
		IOUtils.close(in);
    }

    /**
     * Annotate the specified file/revision.
     *
     * @param file file to annotate
     * @param revision revision to annotate
     * @return file annotation
     * @throws java.io.IOException
     */
    @Override
    public Annotation annotate(File file, String revision) throws IOException {
        ArrayList<String> argv = new ArrayList<>();

        ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
        argv.add(RepoCommand);
        argv.add("annotate");
        argv.add("-nheader");
        argv.add("-out");
        argv.add("-");
        argv.add("-f");
        argv.add("-fmt");
        argv.add("%u|%Vn|");

        if (revision != null) {
            argv.add(revision);
        }
        argv.add(file.getName());
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(file.getParentFile());
        Process process = null;
        try {
            process = pb.start();
            Annotation a = new Annotation(file.getName());
            String line;
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                while ((line = in.readLine()) != null) {
                    String parts[] = line.split("\\|");
        			String aAuthor = parts[0];
                    String aRevision = parts[1];
                    aRevision = aRevision.replace('\\', '/');

                    //
        			// Copyright 2009 to current year.
        			// AVEVA Solutions Ltd and its subsidiaries. All rights reserved.
        			// Modifications by Stefan Eskelid for AVEVA Solutions Ltd.
        			//
        			// Always add username in lower case (for consistency).
        			//
                    a.addLine(aRevision, aAuthor.toLowerCase(), true);
                }
            }
            return a;
        } finally {
            if (process != null) {
                try {
                    process.exitValue();
                } catch (IllegalThreadStateException e) {
                    process.destroy();
                }
            }
        }
    }

    @Override
    public boolean fileHasAnnotation(File file) {
        return true;
    }

    private int waitFor(Process process) {

        do {
            try {
                return process.waitFor();
            } catch (InterruptedException exp) {
            }
        } while (true);
    }

    @SuppressWarnings("PMD.EmptyWhileStmt")
    @Override
    public void update() throws IOException {
        Process process = null;
        try {
            File directory = new File(getDirectoryName());

            // Check if this is a snapshot view
            ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
            String[] argv = {RepoCommand, "catcs"};
            process = Runtime.getRuntime().exec(argv, null, directory);
            boolean snapshot = false;
            String line;
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                while (!snapshot && (line = in.readLine()) != null) {
                    snapshot = line.startsWith("load");
                }
                if (waitFor(process) != 0) {
                    return;
                }
            }
            if (snapshot) {
                // It is a snapshot view, we need to update it manually
                ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
                argv = new String[]{RepoCommand, "update", "-overwrite", "-f"};
                process = Runtime.getRuntime().exec(argv, null, directory);
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    while ((line = in.readLine()) != null) {
                        // do nothing
                    }
                }

                if (waitFor(process) != 0) {
                    return;
                }
            }
        } finally {
            if (process != null) {
                try {
                    process.exitValue();
                } catch (IllegalThreadStateException e) {
                    process.destroy();
                }
            }
        }
    }

    @Override
    public boolean fileHasHistory(File file) {
        // Todo: is there a cheap test for whether ClearCase has history
        // available for a file?
        // Otherwise, this is harmless, since ClearCase's commands will just
        // print nothing if there is no history.
        return true;
    }

    @Override
    public boolean isWorking() {
        if (working == null) {
            ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
            working = checkCmd(RepoCommand, "-version");
        }
        return working;
    }

    @Override
    boolean isRepositoryFor(File file) {
        // if the parent contains a file named "view.dat" or
        // the parent is named "vobs" or the canonical path
        // is found in "cleartool lsvob -s"
    	
    	//
    	// Copyright 2009 to current year.
    	// AVEVA Solutions Ltd and its subsidiaries. All rights reserved.
		// Modifications by Stefan Eskelid for AVEVA Solutions Ltd.
    	//
		// Use parent when detecting repos to get one repo per VOB.
		//
		File parent = file.getParentFile();
		File f = new File(parent, "view.dat");
		try {
			if (parent != null && Files.isSameFile(file.toPath(), f.toPath())) {
				return false;
			}
		} catch (Exception e) {
			// ignore any exceptions, and move along...
		}
		if (parent != null && f.exists()) {
			return true;
		} else if (parent != null && parent.isDirectory() && parent.getName().equalsIgnoreCase("vobs")) {
			return true;
		} else if (isWorking()) {
			try {
				String canonicalPath = file.getCanonicalPath();
				for (String vob : getAllVobs()) {
					if (canonicalPath.equalsIgnoreCase(vob)) {
						return true;
					}
				}
			} catch (IOException e) {
				LOGGER.log(Level.WARNING, "Could not get canonical path for \"" + file + "\"", e);
			}
		}
		
        //
    	// Copyright 2009 to current year.
    	// AVEVA Solutions Ltd and its subsidiaries. All rights reserved.
		// Modifications by Stefan Eskelid for AVEVA Solutions Ltd.
    	//
		// Assume ClearCase repo if the _grandparent_ contains a
        // file named ".specdev":
        // http://www-01.ibm.com/support/docview.wss?uid=swg21135104
		//
        File grandparent = Optional.ofNullable(file.getParentFile())
				.map(File::getParentFile).orElse(null);
		if (grandparent != null && new File(grandparent, ".specdev").exists()) {
			return true;
		}
        return false;
    }

    private static class VobsHolder {

        public static String[] vobs = runLsvob();
    }

    private static String[] getAllVobs() {
        return VobsHolder.vobs;
    }

    private static final ClearCaseRepository testRepo
            = new ClearCaseRepository();

    private static String[] runLsvob() {
        if (testRepo.isWorking()) {
            Executor exec = new Executor(
                    new String[]{testRepo.RepoCommand, "lsvob", "-s"});
            int rc;
            if ((rc = exec.exec(true)) == 0) {
                String output = exec.getOutputString();

                if (output == null) {
                    LOGGER.log(Level.SEVERE,
                            "\"cleartool lsvob -s\" output was null");
                    return new String[0];
                }
                String sep = System.getProperty("line.separator");
                String[] vobs = output.split(Pattern.quote(sep));
                LOGGER.log(Level.CONFIG, "Found VOBs: {0}",
                        Arrays.asList(vobs));
                return vobs;
            }
            LOGGER.log(Level.SEVERE,
                    "\"cleartool lsvob -s\" returned non-zero status: {0}", rc);
        }
        return new String[0];
    }

    @Override
    boolean hasHistoryForDirectories() {
        return true;
    }

    @Override
    History getHistory(File file) throws HistoryException {
        return new ClearCaseHistoryParser().parse(file, this);
    }

    @Override
    String determineParent() throws IOException {
        return null;
    }

    @Override
    String determineBranch() {
        return null;
    }
}
