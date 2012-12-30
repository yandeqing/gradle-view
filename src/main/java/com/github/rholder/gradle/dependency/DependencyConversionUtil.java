package com.github.rholder.gradle.dependency;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * This is a utility class to handle conversion from the dependency tree output
 * given by 'gradle dependencies' into a node graph built on DependencyNode
 * entities.
 */
public class DependencyConversionUtil {

    /**
     * Given the output generated by calling 'gradle dependencies' on a project,
     * return the root node of the dependency graph.
     *
     * @param inputStream where output of 'gradle dependencies' can be read
     * @throws IOException
     */
    public static GradleDependency loadDependenciesFromText(InputStream inputStream) throws IOException {
        List<List<String>> rawDependencies = generateRawDependencies(inputStream);
        GradleDependency root = new GradleDependency("Project Dependencies");
        for(List<String> canonicalRawStringList : rawDependencies) {
            LinkedList<GradleDependency> parentStack = new LinkedList<GradleDependency>();
            GradleDependency scope = extractDependency(root, canonicalRawStringList.remove(0));
            root.dependencies.add(scope);
            parentStack.addFirst(scope);
            for(String rawDependency : canonicalRawStringList) {

                // here we have XXX group:id:version -> replaced_version (*)
                GradleDependency dependency = extractDependency(null, rawDependency);
                if(parentStack.getFirst().level == dependency.level) {
                    processSibling(parentStack, dependency);
                } else if(parentStack.getFirst().level < dependency.level) {
                    processChild(parentStack, dependency);
                } else {
                    // pop stack until we're no longer a sub-dependency
                    while(parentStack.getFirst().level > dependency.level) {
                        parentStack.removeFirst();
                    }

                    // at this point, we could only be a sibling or a child
                    if(parentStack.getFirst().level == dependency.level) {
                        processSibling(parentStack, dependency);
                    } else {
                        processChild(parentStack, dependency);
                    }
                }
            }
        }
        return root;
    }

    /**
     * Return a list of a list of each configuration's dependency lines.  Each
     * Gradle configuration will have its own separate list of all the lines
     * that pertain to that particular configuration (including the line that
     * contains the name and description of the configuration itself as the
     * first entry in the list).
     *
     * @param inputStream where output of 'gradle dependencies' can be read
     * @throws IOException
     */
    private static List<List<String>> generateRawDependencies(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        try {
            String line;
            String previousLine = reader.readLine();
            List<List<String>> rawDependencies = new ArrayList<List<String>>();
            List<String> canonicalRawStrings = null;
            boolean inDependencyTree = false;
            while((line = reader.readLine()) != null) {
                if(!inDependencyTree) {
                    // detect the beginning of a set of dependencies for a given
                    // configuration as the first line beginning with a + when not
                    // inside a dependency tree listing
                    if(line.startsWith("+")) {

                        // collect raw, flattened out dependency entries here
                        canonicalRawStrings = new ArrayList<String>();

                        // previous line will be the configuration
                        // i.e. runtime - Classpath for running the compiled main classes.
                        canonicalRawStrings.add(previousLine);
                        canonicalRawStrings.add(flatten(line));
                        rawDependencies.add(canonicalRawStrings);
                        inDependencyTree = true;
                    } else {
                        previousLine = line;
                    }
                } else {
                    if(line.trim().isEmpty()) {
                        // a blank line kicks us out of dependency parsing
                        inDependencyTree = false;
                    } else {
                        canonicalRawStrings.add(flatten(line));
                    }
                }
            }
            return rawDependencies;
        } finally {
            reader.close();
        }
    }

    /**
     * Parse a raw dependency String into a GradleDependency, setting its
     * parent to the given value.  The raw String would look something like this:
     *
     * XX org.apache.httpcomponents:httpclient:[4.1, 5.0) -> 4.2.1
     *
     * @param parent parent node of this nested dependency
     * @param rawDependency semi-processed raw line output
     */
    private static GradleDependency extractDependency(GradleDependency parent, String rawDependency) {
        String[] nameArray = rawDependency.split(" ");
        String name = flattenName(nameArray);

        String[] splitValues = name.split(":");
        GradleDependency dependency;
        if(splitValues.length < 3) {
            dependency = new GradleDependency(rawDependency);
        } else {
            String version = splitValues[2].trim();
            dependency = new GradleDependency(parent, splitValues[0], splitValues[1], version);
            if(name.contains("(*)")) {
                version = version.replace("(*)", "").trim();
                dependency.omitted = true;
            }

            String[] splitVersion = version.split(" ");
            if(splitVersion.length > 1) {
                if(version.contains("->")) {
                    String[] replaceSplit = version.split("->");
                    version = replaceSplit[0].trim();
                    dependency.replacedByVersion = replaceSplit[1].trim();
                    // TODO add mapping to track which dep actually caused replacement
                }
            }
            dependency.version = version;
            dependency.level = nameArray[0].length();
        }
        return dependency;
    }

    /**
     * Clip off the first XX value from the given array and reconstruct a
     * unified String split by " ".
     *
     * @param nameArray String array to mangle
     */
    private static String flattenName(String[] nameArray) {
        StringBuilder name = new StringBuilder();
        name.append(nameArray[1]);
        for(int i = 2; i < nameArray.length; i++) {
            name.append(" ");
            name.append(nameArray[i]);
        }
        return name.toString();
    }

    /**
     * Flatten and normalize a dependency line by replacing characters used to
     * visually denote the depth with a number of X's that can be more easily
     * counted.
     *
     * @param line single line of gradle output
     */
    private static String flatten(String line) {
        return line
                .replace("\\---", "+---")
                .replace("|    ", "X")
                .replace("     ", "X")
                .replace("+---", "X");
    }

    /**
     * Add the given dependency to its parent and push the dependency onto the
     * parent stack to serve as the new active parent.
     *
     * @param parentStack current parent stack
     * @param dependency child dependency node to process
     */
    private static void processChild(LinkedList<GradleDependency> parentStack, GradleDependency dependency) {
        // add child dependency
        parentStack.getFirst().dependencies.add(dependency);
        dependency.parent = parentStack.getFirst();
        parentStack.addFirst(dependency);
    }

    /**
     * Add the given dependency to the parent of the given parent and push it
     * onto the stack to serve as the new active parent.
     *
     * @param parentStack current parent stack
     * @param dependency sibling dependency node to process
     */
    private static void processSibling(LinkedList<GradleDependency> parentStack, GradleDependency dependency) {
        // sibling dependency, so they share a parent
        GradleDependency sibling = parentStack.removeFirst();
        sibling.parent.dependencies.add(dependency);
        dependency.parent = sibling.parent;

        // new parent on stack is this sibling
        parentStack.addFirst(dependency);
    }
}