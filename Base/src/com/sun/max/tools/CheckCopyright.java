/*
 * Copyright (c) 2011, 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.sun.max.tools;

import java.io.*;
import java.util.*;
import java.util.regex.*;

import com.sun.max.program.*;
import com.sun.max.program.option.*;

/**
 * A program to check the existence and correctness of the copyright notice on a given set of Maxine sources.
 * Sources are defined to be those under management by Mercurial and various options are available
 * to limit the set of sources scanned.
 *
 * @author Mick Jordan
 */

public class CheckCopyright {

    static class YearInfo {

        final int firstYear;
        final int lastYear;

        YearInfo(int firstYear, int lastYear) {
            this.firstYear = firstYear;
            this.lastYear = lastYear;
        }

        @Override
        public boolean equals(Object other) {
            final YearInfo yearInfo = (YearInfo) other;
            return yearInfo.firstYear == firstYear && yearInfo.lastYear == lastYear;
        }

        @Override
        public int hashCode() {
            return firstYear ^ lastYear;
        }
    }

    static class Info extends YearInfo {

        final String fileName;

        Info(String fileName, int firstYear, int lastYear) {
            super(firstYear, lastYear);
            this.fileName = fileName;
        }

        @Override
        public String toString() {
            return fileName + " " + firstYear + ", " + lastYear;
        }
    }

    enum CopyrightKind {
        STAR("star"),
        HASH("hash");

        private static Map<String, CopyrightKind> copyrightMap;
        private static final String COPYRIGHT_REGEX = "Base/.copyright.regex";
        private static final String copyrightFiles = "bin/max|.*/makefile|.*/Makefile|.*\\.sh|.*\\.bash|.*\\.mk|.*\\.java|.*\\.c|.*\\.h";
        private static Pattern copyrightFilePattern;
        private final String suffix;
        private String copyright;
        Pattern copyrightPattern;

        CopyrightKind(String suffix) {
            this.suffix = suffix;
        }

        void readCopyright()  throws IOException {
            final File file = new File(COPYRIGHT_REGEX + "." + suffix);
            assert file.exists();
            byte[] b = new byte[(int) file.length()];
            FileInputStream is = new FileInputStream(file);
            is.read(b);
            is.close();
            copyright = new String(b);
            copyrightPattern = Pattern.compile(copyright, Pattern.DOTALL);
        }

        /**
         * Return the modification year from copyright.
         *
         * @param fileContent
         * @return modification year, or 0 if copyright not expected, or -1 if malformed or missing copyright.
         */

        static int getCopyright(String fileName, String fileContent) {
            if (copyrightMap == null) {
                copyrightFilePattern = Pattern.compile(copyrightFiles);
                copyrightMap = new HashMap<String, CopyrightKind>();
                copyrightMap.put("java", CopyrightKind.STAR);
                copyrightMap.put("c", CopyrightKind.STAR);
                copyrightMap.put("h", CopyrightKind.STAR);
                copyrightMap.put("mk", CopyrightKind.HASH);
                copyrightMap.put("sh", CopyrightKind.HASH);
                copyrightMap.put("bash", CopyrightKind.HASH);
                copyrightMap.put("", CopyrightKind.HASH);
            }
            if (!copyrightFilePattern.matcher(fileName).matches()) {
                return 0;
            }
            final String extension = getExtension(fileName);
            CopyrightKind ck = copyrightMap.get(extension);
            assert ck != null;
            if (ck.copyrightPattern.matcher(fileContent).matches()) {
                final int lx = getModifiedYearIndex(fileContent);
                return Integer.parseInt(fileContent.substring(lx, lx + 4));
            }
            return -1;
        }

        private static String getExtension(String fileName) {
            int index = fileName.lastIndexOf('.');
            if (index > 0) {
                return fileName.substring(index + 1);
            }
            return "";
        }

        static int getModifiedYearIndex(String fileContent) {
            return fileContent.indexOf("20", fileContent.indexOf("20") + 4);
        }
    }

    private static List<YearInfo> infoList = new ArrayList<YearInfo>();
    private static int currentYear = Calendar.getInstance().get(Calendar.YEAR);
    private static final OptionSet options = new OptionSet(true);
    private static final Option<Boolean> help = options.newBooleanOption("help", false, "Show help message and exit.");
    private static final Option<List<String>> FILES_TO_CHECK = options.newStringListOption("files",
                    null, ',', "list of files to check");
    private static final Option<String> FILE_LIST = options.newStringOption("filelist",
                    null, "file containing list of files to check");
    private static final Option<Boolean> HG_ALL = options.newBooleanOption("all", false, "check all hg managed files requiring a copyright (hg status --all)");
    private static final Option<Boolean> HG_MODIFIED = options.newBooleanOption("modified", false, "check all modified hg managed files requiring a copyright (hg status)");
    private static final Option<Boolean> HG_OUTGOING = options.newBooleanOption("outgoing", false, "check outgoing hg managed files requiring a copyright (hg outgoing)");
    private static final Option<Integer> HG_LOG = options.newIntegerOption("last", 0, "check hg managed files requiring a copyright in last N changesets (hg log -l N)");
    private static final Option<List<String>> PROJECT = options.newStringListOption("projects", null, ',', "filter files to specific projects");
    private static final Option<String> OUTGOING_REPO = options.newStringOption("repo", null, "override outgoing repository");
    private static final Option<Boolean> EXHAUSTIVE = options.newBooleanOption("exhaustive", false, "check all hg managed files");
    private static final Option<Boolean> FIX = options.newBooleanOption("fix", false, "fix copyright errors");
    private static boolean error;


    public static void main(String[] args) {
        Trace.addTo(options);
        // parse the arguments
        options.parseArguments(args).getArguments();
        if (help.getValue()) {
            options.printHelp(System.out, 100);
            return;
        }

        try {
            CopyrightKind.STAR.readCopyright();
            CopyrightKind.HASH.readCopyright();
            List<String> filesToCheck = null;
            if (HG_ALL.getValue()) {
                filesToCheck = getAllFiles(true);
            } else if (HG_OUTGOING.getValue()) {
                filesToCheck = getOutgoingFiles();
            } else if (HG_MODIFIED.getValue()) {
                filesToCheck = getAllFiles(false);
            } else if (HG_LOG.getValue() > 0) {
                filesToCheck = getLastNFiles(HG_LOG.getValue());
            } else if (FILE_LIST.getValue() != null) {
                filesToCheck = readFileList(FILE_LIST.getValue());
            } else {
                filesToCheck = FILES_TO_CHECK.getValue();
            }
            if (filesToCheck != null && filesToCheck.size() > 0) {
                processFiles(filesToCheck);
            } else {
                System.out.println("nothing to check");
            }
            System.exit(error ? 1 : 0);
        } catch (Exception ex) {
            ProgramError.unexpected("processing failed", ex);
        }
    }

    private static void processFiles(List<String> fileNames) throws Exception {
        final List<String> projects = PROJECT.getValue();
        for (String fileName : fileNames) {
            if (projects == null || isInProjects(fileName, projects)) {
                Trace.line(1, "checking " + fileName);
                final List<String> logInfo = hglog(fileName);
                final Info info = getInfo(fileName, true, logInfo);
                checkFile(fileName, info);
            }
        }
    }

    private static boolean isInProjects(String fileName, List<String> projects) {
        final int ix = fileName.indexOf(File.separatorChar);
        assert ix > 0;
        final String fileProject = fileName.substring(0, ix);
        for (String project : projects) {
            if (fileProject.equals(project)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> readFileList(String fileListName) throws IOException {
        final List<String> result = new ArrayList<String>();
        BufferedReader b = null;
        try {
            b = new BufferedReader(new FileReader(fileListName));
            while (true) {
                final String fileName = b.readLine();
                if (fileName == null) {
                    break;
                }
                if (fileName.length() == 0) {
                    continue;
                }
                result.add(fileName);
            }
        } finally {
            if (b != null) {
                b.close();
            }
        }
        return result;
    }

    private static Info getInfo(String fileName, boolean lastOnly, List<String> logInfo) {
        // process sequence of changesets
        int lastYear = 0;
        int firstYear = 0;
        String summary = null;
        int ix = 0;

        while (ix < logInfo.size()) {
            String s = logInfo.get(ix++);
            assert s.startsWith("changeset");
            // process every entry in a given change set
            s = logInfo.get(ix++);
            if (s.startsWith("branch")) {
                s = logInfo.get(ix++);
            }
            while (s.startsWith("parent")) {
                s = logInfo.get(ix++);
            }
            if (s.startsWith("tag")) {
                s = logInfo.get(ix++);
            }
            assert s.startsWith("user");
            s = logInfo.get(ix++);
            assert s.startsWith("date");
            final int csYear = getYear(s);
            summary = logInfo.get(ix++);
            assert summary.startsWith("summary");
            s = logInfo.get(ix++); // blank
            assert s.length() == 0;
            if (lastYear == 0 && summary.contains("change all copyright notices from Sun to Oracle")) {
                // special case of last change being the copyright change, which didn't
                // count as a change of last modification date!
                continue;
            }
            if (lastYear == 0) {
                lastYear = csYear;
                firstYear = lastYear;
            } else {
                firstYear = csYear;
            }
            // if we only want the last modified year, quit now
            if (lastOnly) {
                break;
            }

        }

        // Special case
        if (summary != null && summary.contains("Initial commit of VM sources")) {
            firstYear = 2007;
        }
        return new Info(fileName, firstYear, lastYear);
    }

    private static int getYear(String dateLine) {
        final String[] parts = dateLine.split(" ");
        assert parts[parts.length - 2].startsWith("20");
        return Integer.parseInt(parts[parts.length - 2]);
    }

    private static void checkFile(String c, Info info) throws IOException {
        String fileName = info.fileName;
        File file = new File(fileName);
        assert file.exists();
        int fileLength = (int) file.length();
        byte[] b = new byte[fileLength];
        FileInputStream is = new FileInputStream(file);
        is.read(b);
        is.close();
        final String fileContent = new String(b);
        int mYear = CopyrightKind.getCopyright(fileName, fileContent);
        if (mYear > 0) {
            if ((mYear != info.lastYear) || (HG_MODIFIED.getValue() && mYear != currentYear)) {
                System.out.println(fileName + " copyright last modified year " + mYear + ", hg last modified year " + (HG_MODIFIED.getValue() ? currentYear : info.lastYear));
                if (FIX.getValue()) {
                    // Use currentYear as that is what it will be when it's checked in!
                    System.out.println("updating last modified year of " + fileName + " to " + currentYear);
                    final int lx = CopyrightKind.getModifiedYearIndex(fileContent);
                    final String newContent = fileContent.substring(0, lx) + info.lastYear + fileContent.substring(lx + 4);
                    final FileOutputStream os = new FileOutputStream(file);
                    os.write(newContent.getBytes());
                    os.close();
                } else {
                    error = true;
                }
            }
        } else {
            if (mYear < 0 || EXHAUSTIVE.getValue()) {
                System.out.println("ERROR: file " + fileName + " has no copyright");
                error = true;
            }
        }
    }


    private static List<String> hglog(String fileName) throws Exception {
        final String[] cmd = new String[] {"hg", "log", "-f", fileName};
        return exec(null, cmd, true);
    }

    private static List<String> getLastNFiles(int n) throws Exception {
        final String[] cmd = new String[] {"hg", "log", "-v", "-l", Integer.toString(n)};
        return getFilesFiles(exec(null, cmd, false));
    }

    private static List<String> getAllFiles(boolean all) throws Exception {
        final String[] cmd;
        if (HG_MODIFIED.getValue()) {
            cmd = new String[] {"hg",  "status"};
        } else {
            cmd = new String[] {"hg",  "status",  "--all"};
        }
        List<String> output = exec(null, cmd, true);
        final List<String> result = new ArrayList<String>(output.size());
        for (String s : output) {
            final char ch = s.charAt(0);
            if (!(ch == 'I' || ch == '?' ||  ch == '!')) {
                result.add(s.substring(2));
            }
        }
        return result;
    }

    private static List<String> getOutgoingFiles() throws Exception {
        final String[] cmd;
        if (OUTGOING_REPO.getValue() == null) {
            cmd = new String[] {"hg",  "-v", "outgoing"};
        } else {
            cmd = new String[] {"hg",  "-v", "outgoing", OUTGOING_REPO.getValue()};
        }

        final List<String> output = exec(null, cmd, false); // no outgoing exits with result 1
        return getFilesFiles(output);
    }

    private static List<String> getFilesFiles(List<String> output) {
        // there may be multiple changesets so merge the "files:"
        final Map<String, String> outSet = new TreeMap<String, String>();
        for (String s : output) {
            if (s.startsWith("files:")) {
                int ix = s.indexOf(' ');
                while (ix < s.length() && s.charAt(ix) == ' ') {
                    ix++;
                }
                final String[] files = s.substring(ix).split(" ");
                for (String file : files) {
                    outSet.put(file, file);
                }
            }
        }
        return new ArrayList<String>(outSet.values());
    }

    private static List<String> exec(File workingDir, String[] command, boolean failExit) throws IOException, InterruptedException {
        List<String> result = new ArrayList<String>();
        if (Trace.hasLevel(2)) {
            Trace.line(2, "Executing process in directory: " + workingDir);
            for (String c : command) {
                Trace.line(2, "  " + c);
            }
        }
        final Process process = Runtime.getRuntime().exec(command, null, workingDir);
        try {
            result = readOutput(process.getInputStream());
            final int exitValue = process.waitFor();
            if (exitValue != 0 && failExit) {
                final List<String> errorResult = readOutput(process.getErrorStream());
                System.err.print("execution of command: ");
                for (String c : command) {
                    System.err.print(c);
                    System.err.print(' ');
                }
                System.err.println("failed with result " + exitValue);
                for (String e : errorResult) {
                    System.err.println(e);
                }
                ProgramError.unexpected("terminating");
            }
        } finally {
            process.destroy();
        }
        return result;
    }

    private static List<String> readOutput(InputStream is) throws IOException {
        final List<String> result = new ArrayList<String>();
        BufferedReader bs = null;
        try {
            bs = new BufferedReader(new InputStreamReader(is));
            while (true) {
                final String line = bs.readLine();
                if (line == null) {
                    break;
                }
                result.add(line);
            }
        } finally {
            if (bs != null) {
                bs.close();
            }
        }
        return result;
    }

}