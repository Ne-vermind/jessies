package terminator;

import java.io.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import e.util.*;
import terminator.view.*;

public class Terminator {
	private static final Terminator INSTANCE = new Terminator();
	private List arguments;
	private ArrayList frames = new ArrayList();
	
	public static Terminator getSharedInstance() {
		return INSTANCE;
	}
	
	private Terminator() {
		Log.setApplicationName("Terminator");
	}
	
	private void parseCommandLine(final String[] argumentArray) throws IOException {
		arguments = Options.getSharedInstance().parseCommandLine(argumentArray);
		if (arguments.contains("-h") || arguments.contains("-help") || arguments.contains("--help")) {
			showUsage(System.err);
		}
		if (arguments.contains("-v") || arguments.contains("-version") || arguments.contains("--version")) {
			showVersion();
		}
		initUi();
	}
	
	public void frameClosed(TerminatorFrame frame) {
		frames.remove(frame);
		if (frames.size() == 0) {
			System.exit(0);  // Check if on Mac OS X, and exit conditionally.
		}
	}
	
	public void openFrame() {
		JTerminalPaneFactory pane = new JTerminalPaneFactory.Shell();
		TerminatorFrame frame = new TerminatorFrame(this, new JTerminalPaneFactory[] { pane });
		frames.add(frame);
	}
	
	/**
	 * Sets up the user interface on the AWT event thread. I've never
	 * seen this matter in practice, but strictly speaking, you're
	 * supposed to do this.
	 */
	private void initUi() {
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				JTerminalPaneFactory[] terminals = getInitialTerminals();
				TerminatorFrame frame = new TerminatorFrame(Terminator.this, terminals);
				frames.add(frame);
			}
		});
	}
	
	private JTerminalPaneFactory[] getInitialTerminals() {
		ArrayList result = new ArrayList();
		String name = null;
		for (int i = 0; i < arguments.size(); ++i) {
			String word = (String) arguments.get(i);
			if (word.equals("-n")) {
				name = (String) arguments.get(++i);
				continue;
			}
			
			String command = word;
			result.add(new JTerminalPaneFactory.Command(command, name));
			name = null;
		}
		
		if (arguments.isEmpty()) {
			result.add(new JTerminalPaneFactory.Shell());
		}
		return (JTerminalPaneFactory[]) result.toArray(new JTerminalPaneFactory[result.size()]);
	}

	public void showUsage(PrintStream out) {
		out.println("Usage: Terminator [--help] [-xrm <resource-string>]... [[-n <name>] command]...");
		out.println();
		out.println("Current resource settings:");
		Options.getSharedInstance().showOptions(out);
		out.println();
		out.println("Terminator will read your .Xdefaults and .Xresources files, and use");
		out.println("resources of class Rxvt, Terminator or XTerm.");
		System.exit(0);
	}
	
	public void showVersion() {
		System.err.println("Terminator (see ChangeLog for version information)");
		System.err.println("Copyright (C) 2004 Free Software Foundation, Inc.");
		System.err.println("This is free software; see the source for copying conditions.  There is NO");
		System.err.println("warranty; not even for MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.");
		System.exit(0);
	}

	public static void main(final String[] arguments) throws IOException {
		Terminator.getSharedInstance().parseCommandLine(arguments);
	}
}
