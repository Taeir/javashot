/**
 * Copyright (C) 2009 Samir Chekkal, 2021 Taico Aerts
 * This program is distributed under the terms of the GNU General Public License.
 */
package nl.taico.javashot.graph;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import nl.taico.javashot.util.Properties;

/**
 * Class used to keep track of called methods and classes and to write the end results in a ".dot" file which can be visualized by tools from
 * (http://www.graphviz.org/) and also manipulated by standard unix tools such as grep, awk, .... Every instrumented method will call the static
 * method Graph.pushNode at its start and the static Graph.popNode at its exit. The capture is not started until a class with the short name equals to
 * that of the configuration property startCaptureAt is called. Once the capture is started a ".dot" file is created in the directory designed by
 * variable captureHome inside a directory designing the current day, the name of the ".dot" file is a concatenation of the short name of the class
 * which fired the capture plus the hours plus minute plus seconds, the file is saved to disk when we encounter the note START in the stack .
 */
public class Graph {

	/**
	 * Stack used to keep track of the nodes
	 */
	private static final ThreadLocal<Stack<String>> stack = ThreadLocal.withInitial(Stack::new);
	/**
	 * The first node pushed to the stack at the start of the capture, when we encounter it the next time we know that current capture has ended, so
	 * we create another file for the next capture.
	 */
	private static final String START = "_START_";
	/**
	 * The directory in which are stored ".dot" files. It is the capture directory inside the directory designed by the System property JAVASHOT_HOME
	 */
	private static final String captureHome = Properties.getJavashotHome() + "/capture/";
	/**
	 * The short name of the class at which the capture will start
	 */
	private static final String startCaptureAt = Properties.getStartCaptureAt();
	/**
	 * The record the full name of the class instead of a short one
	 */
	private static final boolean useFullPackageClassName = Properties.getUseFullPackageClassName();
	/**
	 * The capture is started when ever we find the class designed by startCaptureAt
	 */
	private static final ThreadLocal<Boolean> captureStarted = new ThreadLocal<>();
	/**
	 * Sequence id of method calls
	 */
	private static final ThreadLocal<Long> sequenceId = new ThreadLocal<>();

	/**
	 * Generator for unique ids for the threads.
	 */
	private static final AtomicInteger threadIdGenerator = new AtomicInteger();
	/**
	 * Thread identifier
	 */
	private static final ThreadLocal<Integer> threadIdentifier = ThreadLocal.withInitial(threadIdGenerator::getAndIncrement);
	/**
	 * The .dot file containing the description of the graph in the dot language. This file can be viewed by tools from (http://www.graphviz.org/) and
	 * also manipulated by standard unix tools such as grep, awk, ... to extract the desired information from a huge file
	 */
	private static final ThreadLocal<BufferedWriter> dotFile = new ThreadLocal<>();

	/**
	 * This method adds a new node to the graph of the currently executing thread.
	 *
	 * @param className the name of the class
	 * @param methodeName the name of the method that was called
	 */
	public static void pushNode(String className, String methodeName) {
		if (!useFullPackageClassName) {
			className = className.substring(className.lastIndexOf('.') + 1);
		}

		// If not started yet, initialize the trace
		Boolean captureStartedTemp = captureStarted.get();
		if ((captureStartedTemp == null || !captureStartedTemp) && startCaptureAt.equalsIgnoreCase(className)) {
			initializeTrace();
		}

		// Check if this was interesting to us
		captureStartedTemp = captureStarted.get();
		if (captureStartedTemp == null || !captureStartedTemp) return;

		// Write the current line
		try {
			String buffer = stack.get().peek() + "->" + className + "[label=\"" + sequenceId.get() + ":" + methodeName + "\"]\n";
			sequenceId.set(sequenceId.get() + 1);
			dotFile.get().write(buffer);
		} catch (IOException ex) {
			ex.printStackTrace();
			throw new RuntimeException("ERROR: Unable to write trace to dotfile", ex);
		}
		stack.get().push(className);
	}

	/**
	 * Initializes the start of a new trace.
	 */
	private static void initializeTrace() {
		// Determine the file to save this trace to
		String todayDir = createTodayDirectory();
		Calendar today = Calendar.getInstance();
		String file = captureHome + todayDir + "Thread_" + threadIdentifier.get() + "_" + startCaptureAt + "_" + String.format("%tH", today) + String.format("%tM", today) + String.format("%tS", today) + ".dot";

		// Start writing the initial parts of the graph to file.
		try {
			Graph.dotFile.set(new BufferedWriter(new FileWriter(file)));
			dotFile.get().write("digraph " + startCaptureAt + "{\n");
		} catch (IOException ex) {
			ex.printStackTrace();
			throw new RuntimeException("ERROR: Unable to initialize dotfile at \"" + file + "\"", ex);
		}

		captureStarted.set(Boolean.TRUE);
		sequenceId.set(1L);
		stack.get().push(START);
	}

	/**
	 * Create a directory for today's captures if it not already exists
	 */
	private static String createTodayDirectory() {
		SimpleDateFormat formater = new SimpleDateFormat("ddMMyyyy");
		String todayDir = formater.format(new java.util.Date()) + "/";
		File file = new File(captureHome + todayDir);
		file.mkdir();
		return todayDir;
	}

	/**
	 * Finishes the current node.
	 */
	public static void popNode() {
		// Ignore if not capturing
		final Boolean captureStartedTemp = captureStarted.get();
		if (captureStartedTemp == null || !captureStartedTemp) return;

		String node = stack.get().pop();
		try {
			String buffer = node + "->" + stack.get().peek() + "[label=\"" + sequenceId.get() + "\", style=dashed]\n";
			sequenceId.set(sequenceId.get() + 1);
			dotFile.get().write(buffer);
		} catch (IOException ex) {
			ex.printStackTrace();
			throw new RuntimeException("ERROR: Unable to write to dotfile", ex);
		}

		//Finalize the trace.
		if (START.equalsIgnoreCase(stack.get().peek())) {
			captureStarted.set(Boolean.FALSE);
			try {
				dotFile.get().write("}");
				Graph.dotFile.get().close();
			} catch (IOException ex) {
				ex.printStackTrace();
				throw new RuntimeException("ERROR: Unable to finalize dotfile!", ex);
			}
		}
	}
}
