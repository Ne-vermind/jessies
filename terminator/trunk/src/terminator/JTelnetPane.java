package terminatorn;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import javax.swing.*;

/**

@author Phil Norman
@author Elliott Hughes
*/

public class JTelnetPane extends JPanel {
	private TelnetControl control;
	private JTextBuffer textPane;
	private String name;
	
	/**
	 * Creates a new terminal with the given name, running the given command.
	 */
	private JTelnetPane(String name, String command) {
		super(new BorderLayout());
		this.name = name;
		
		try {
			Log.warn("Starting process '" + command + "'");
			final Process proc = Runtime.getRuntime().exec(System.getProperty("pty.binary") + " " + command);
			init(command, proc.getInputStream(), proc.getOutputStream());
			// Probably should do this somewhere else rather than setting up a whole Thread for it.
			Thread reaper = new Thread(new Runnable() {
				public void run() {
					try {
						int status = proc.waitFor();
						if (status == 0) {
							closeContainingTabOrWindow();
						} else {
							control.announceConnectionLost("[Process exited with status " + status + ".]");
						}
					} catch (Exception ex) {
						ex.printStackTrace();
					}
				}
			});
			reaper.start();
		} catch (IOException ex) {
			ex.printStackTrace();
		}
	}
	
	/**
	 * Creates a new terminal running the given command, with the given
	 * title. If 'title' is null, we use the first word of the command
	 * as the the title.
	 */
	public static JTelnetPane newCommandWithTitle(String command, String title) {
		if (title == null) {
			title = command.trim();
			if (title.indexOf(' ') != -1) {
				title = title.substring(0, title.indexOf(' '));
			}
		}
		return new JTelnetPane(title, command);
	}
	
	/**
	 * Creates a new terminal running the user's shell.
	 */
	public static JTelnetPane newShell() {
		String user = System.getProperty("user.name");
		String command = getUserShell(user);
		if (Options.getSharedInstance().isLoginShell()) {
			command += " -l";
		}
		return new JTelnetPane(user + "@localhost", command);
	}
	
	/**
	* Returns the command to execute as the user's shell, parsed from the /etc/passwd file.
	* On any kind of failure, 'bash' is returned as default.
	*/
	private static String getUserShell(String user) {
		File passwdFile = new File("/etc/passwd");
		if (passwdFile.exists()) {
			BufferedReader in = null;
			try {
				in = new BufferedReader(new FileReader(passwdFile));
				String line;
				while ((line = in.readLine()) != null) {
					if (line.startsWith(user + ":")) {
						return line.substring(line.lastIndexOf(':') + 1);
					}
				}
			} catch (IOException ex) {
				ex.printStackTrace();
			} finally {
				if (in != null) {
					try {
						in.close();
					} catch (IOException ex) { }
				}
			}
		}
		return "bash";
	}

	private void init(String command, InputStream in, OutputStream out) throws IOException {
		textPane = new JTextBuffer();
		textPane.addComponentListener(new ComponentAdapter() {
			private Dimension currentSize;
			public void componentShown(ComponentEvent e) {
				this.currentSize = textPane.getSizeInCharacters();
			}
			public void componentResized(ComponentEvent e) {
				Dimension size = textPane.getSizeInCharacters();
				if (size.equals(currentSize) == false) {
					// FIXME: need to tell pty about the size change.
					currentSize = size;
				}
			}
		});
		textPane.addKeyListener(new KeyHandler());
		
		JScrollPane scrollPane = new JScrollPane(new BorderPanel(textPane));
		scrollPane.setBorder(null);
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
		scrollPane.getViewport().setBackground(textPane.getBackground());
		
		fixScrollBarForMacOs(scrollPane);
		
		add(scrollPane, BorderLayout.CENTER);
		
		textPane.sizeChanged();
		try {
			control = new TelnetControl(textPane.getModel(), command, in, out);
		} catch (IOException ex) {
			ex.printStackTrace();
		}
	}
	
	/**
	 * Mac OS' grow box intrudes in the lower right corner of every window.
	 * In our case, with a scroll bar hard against the right edge of the
	 * window, that means our down scroll arrow gets covered.
	 */
	private void fixScrollBarForMacOs(JScrollPane scrollPane) {
		if (System.getProperty("os.name").indexOf("Mac OS") == -1) {
			return;
		}
		
		// Make a JPanel the same size as the grow box.
		final int size = (int) scrollPane.getVerticalScrollBar().getMinimumSize().getWidth();
		JPanel growBoxPanel = new JPanel();
		Dimension growBoxSize = new Dimension(size, size);
		growBoxPanel.setPreferredSize(growBoxSize);
		
		// Stick the scroll pane's scroll bar in a new panel with
		// our fake grow box at the bottom, so the real grow box
		// can sit on top of it.
		JPanel sidePanel = new JPanel(new BorderLayout());
		sidePanel.add(scrollPane.getVerticalScrollBar(), BorderLayout.CENTER);
		sidePanel.add(growBoxPanel, BorderLayout.SOUTH);
		
		// Put our scroll bar plus spacer panel against the edge of
		// the window.
		add(sidePanel, BorderLayout.EAST);
	}
	
	/** Starts the process listening once all the user interface stuff is set up. */
	public void start() {
		control.start();
	}
	
	/**
	 * Closes the tab or window containing this terminal.
	 */
	public void closeContainingTabOrWindow() {
		// Look for a tabbed pane to remove ourselves from first,
		// but only one with other tabs; if we're the last tab, we
		// should fall through and close the window instead.
		JTabbedPane tabbedPane = (JTabbedPane) SwingUtilities.getAncestorOfClass(JTabbedPane.class, this);
		if (tabbedPane != null && tabbedPane.getTabCount() > 1) {
			tabbedPane.remove(this);
			return;
		}
		
		// Look for a window to close.
		JFrame window = (JFrame) SwingUtilities.getAncestorOfClass(JFrame.class, this);
		if (window != null) {
			window.setVisible(false);
			window.dispose();
		}
	}
	
	public String getName() {
		return name;
	}
	
	public Dimension getOptimalViewSize() {
		return textPane.getOptimalViewSize();
	}
	
	private class KeyHandler implements KeyListener {
		public void keyPressed(KeyEvent event) {
			String sequence = getSequenceForKeyCode(event);
			if (sequence != null) {
				control.sendEscapeString(sequence);
				scroll();
				event.consume();
			}
		}

		private String getSequenceForKeyCode(KeyEvent event) {
			int keyCode = event.getKeyCode();
			switch (keyCode) {
				case KeyEvent.VK_ESCAPE: return "";
				
				case KeyEvent.VK_HOME: return "[H";
				case KeyEvent.VK_END: return "[F";
				
				case KeyEvent.VK_UP:
				case KeyEvent.VK_DOWN:
				case KeyEvent.VK_RIGHT:
				case KeyEvent.VK_LEFT:
				{
					/* Send xterm sequences. */
					char letter = "DACB".charAt(keyCode - KeyEvent.VK_LEFT);
					if (event.isControlDown()) {
						return "[5" + letter;
					} else {
						return "[" + letter;
					}
				}

				default: return null;
			}
		}

		public void keyReleased(KeyEvent event) {
		}

		public void keyTyped(KeyEvent event) {
			char ch = event.getKeyChar();
//			System.err.println("Got key " + ((int) ch));
			if (ch != KeyEvent.CHAR_UNDEFINED) {
				control.sendChar(ch);
				scroll();
			}
			event.consume();
		}
		
		/**
		 * Scrolls the display to the bottom if we're configured to do so.
		 * This should be invoked after any action is performed as a
		 * result of a key press/release/type.
		 */
		public void scroll() {
			if (Options.getSharedInstance().isScrollKey()) {
				textPane.scrollToBottom();
			}
		}
	}
	
	/**
	 * Hands focus to our text pane.
	 */
	public void requestFocus() {
		textPane.requestFocus();
	}
}
