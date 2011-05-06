package terminator.view;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import e.gui.*;
import e.util.*;

import terminator.*;
import terminator.model.*;
import terminator.terminal.*;
import terminator.view.highlight.*;

public class TerminalView extends JComponent implements FocusListener, Scrollable {
    private static final Stopwatch paintComponentStopwatch = Stopwatch.get("TerminalView.paintComponent");
    private static final Stopwatch paintStyledTextStopwatch = Stopwatch.get("TerminalView.paintStyledText");
    
    private TerminalModel model;
    private Location cursorPosition = new Location(0, 0);
    private boolean displayCursor = true;
    private boolean blinkOn = true;
    private CursorBlinker cursorBlinker;

    private SelectionHighlighter selectionHighlighter;
    private FindHighlighter findHighlighter;
    private UrlHighlighter urlHighlighter;

    // TODO: show the current selection like Evergreen does, and maybe the range of visible lines too?
    private BirdView birdView;
    private FindBirdsEye birdsEye;
    
    // Size may be smaller than model's lines. Elements may be null, but should not be empty.
    // FIXME: this is a mistake:
    // 1. Lines with matches/URLs are very rare, so we shouldn't waste space on lines with no matches.
    // 2. We should use List<Range> rather than Range[].
    // 3. Using null instead of an empty array or list is gross (but any fix for #1 probably fixes this).
    private final ArrayList<Range[]> urlMatches = new ArrayList<Range[]>();
    private final ArrayList<Range[]> findMatches = new ArrayList<Range[]>();
    
    // If non-null, the row of this is mouseLocation.getLineIndex()
    private Range urlUnderMouse = null;
    // Init line index to 0 so we never need to check if it's a valid line index, but don't have a valid char offset.
    private Location mouseLocation = new Location(0, -1);
    
    public TerminalView() {
        TerminatorPreferences preferences = Terminator.getPreferences();
        // The background is no longer set in optionsDidChange
        // The background must be set before the model is created, or Bad Things happen
        setBackground(preferences.getColor("background"));
        this.model = new TerminalModel(this, preferences.getInt(TerminatorPreferences.INITIAL_COLUMN_COUNT), preferences.getInt(TerminatorPreferences.INITIAL_ROW_COUNT));
        ComponentUtilities.disableFocusTraversal(this);
        setBorder(BorderFactory.createEmptyBorder(1, 4, 4, 4));
        setOpaque(true);
        optionsDidChange();
        addFocusListener(this);
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                requestFocus();
                if (SwingUtilities.isLeftMouseButton(event) && urlUnderMouse != null && event.isControlDown()) {
                    try {
                        TextLine line = model.getTextLine(mouseLocation.getLineIndex());
                        String url = line.getTabbedString(urlUnderMouse.getStart(), urlUnderMouse.getEnd());
                        BrowserLauncher.openURL(url);
                    } catch (Throwable th) {
                        SimpleDialog.showDetails(TerminalView.this, "Problem opening URL", th);
                    }
                }
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent event) {
                Location location = viewToModel(event.getPoint());
                if (location.equals(mouseLocation)) {
                    return;
                }
                // Lines to be repainted:
                // The line the mouse left, if there was a URL
                // The line the mouse entered, if there is a URL
                if (urlUnderMouse != null) {
                    repaintLine(mouseLocation.getLineIndex());
                }
                urlUnderMouse = getUrlForLocation(location);
                mouseLocation = location;
                if (urlUnderMouse != null) {
                    repaintLine(mouseLocation.getLineIndex());
                }
                // There's a minor problem with setting the cursor in the mouse motion listener: It doesn't change the cursor if a URL is created or destroyed by additional output.
                // Still, that's a small price to pay.
                setCursor(urlUnderMouse != null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor() );
            }
        });
        addMouseWheelListener(HorizontalScrollWheelListener.INSTANCE);
        findHighlighter = new FindHighlighter();
        urlHighlighter = new UrlHighlighter();
        becomeDropTarget();
        cursorBlinker = new CursorBlinker(this);
        selectionHighlighter = new SelectionHighlighter(this);
        birdsEye = new FindBirdsEye(this);
    }
    
    public void optionsDidChange() {
        TerminatorPreferences preferences = Terminator.getPreferences();
        setFont(preferences.getFont(TerminatorPreferences.FONT));
        sizeChanged();
    }
    
    public BirdsEye getBirdsEye() {
        return birdsEye;
    }
    
    public void setBirdView(BirdView birdView) {
        this.birdView = birdView;
    }
    
    public SelectionHighlighter getSelectionHighlighter() {
        return selectionHighlighter;
    }
    
    public UrlHighlighter getUrlHighlighter() {
        return urlHighlighter;
    }

    public FindHighlighter getFindHighlighter() {
        return findHighlighter;
    }

    public void userIsTyping() {
        blinkOn = true;
        redrawCursorPosition();
        if (Terminator.getPreferences().getBoolean(TerminatorPreferences.HIDE_MOUSE_WHEN_TYPING)) {
            setCursor(GuiUtilities.INVISIBLE_CURSOR);
        }
    }
    
    private void becomeDropTarget() {
        new TerminalDropTarget(this);
    }
    
    public TerminalModel getModel() {
        return model;
    }
    
    /**
     * Pastes the text on the clipboard into the terminal.
     */
    public void pasteSystemClipboard() {
        pasteClipboard(getToolkit().getSystemClipboard());
    }
    
    /**
     * Pastes the system selection, generally only available on X11.
     */
    public void pasteSystemSelection() {
        Clipboard systemSelection = getToolkit().getSystemSelection();
        if (systemSelection != null) {
            pasteClipboard(systemSelection);
        }
    }
    
    /**
     * Pastes the system selection on X11.
     * Pastes the clipboard on Windows, like PuTTY.
     * Does nothing on Mac OS, whereas Terminal pastes the selection from the current terminal.
     */
    public void middleButtonPaste() {
        if (GuiUtilities.isWindows()) {
            pasteSystemClipboard();
        } else {
            pasteSystemSelection();
        }
    }
        
    private void pasteClipboard(Clipboard clipboard) {
        try {
            Transferable contents = clipboard.getContents(this);
            String string = (String) contents.getTransferData(DataFlavor.stringFlavor);
            terminalControl.sendUtf8String(string);
        } catch (Exception ex) {
            Log.warn("Couldn't paste.", ex);
        }
    }
    
    private TerminalControl terminalControl;
    
    public TerminalControl getTerminalControl() {
        return terminalControl;
    }
    
    public void setTerminalControl(TerminalControl terminalControl) {
        this.terminalControl = terminalControl;
    }
    
    /** Returns our visible size. */
    public Dimension getVisibleSize() {
        JScrollPane scrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, this);
        return scrollPane.getViewport().getExtentSize();
    }
    
    /**
     * Returns the dimensions of an average character. Note that even though
     * we use a fixed-width font, some glyphs for non-ASCII characters can
     * be wider than this. See Markus Kuhn's UTF-8-demo.txt for examples,
     * particularly among the Greek (where some glyphs are normal-width
     * and others are wider) and Japanese (where most glyphs are wide).
     * 
     * This isn't exactly deprecated, but you should really think hard
     * before using it.
     */
    public Dimension getCharUnitSize() {
        FontMetrics metrics = getFontMetrics(getFont());
        int width = metrics.charWidth('W');
        int height = metrics.getHeight();
        // Avoid divide by zero errors, so the user gets a chance to change their font.
        if (width == 0) {
            Log.warn("Insane font width for " + getFont());
            width = 1;
        }
        if (height == 0) {
            Log.warn("Insane font height for " + getFont());
            height = 1;
        }
        return new Dimension(width, height);
    }
    
    /**
     * Returns our size in character units, where 'width' is the number of
     * columns and 'height' the number of rows. (In case you were concerned
     * about the fact that terminals tend to refer to y,x coordinates.)
     */
    public Dimension getVisibleSizeInCharacters() {
        Dimension result = getVisibleSize();
        Insets insets = getInsets();
        result.width -= (insets.left + insets.right);
        result.height -= (insets.top + insets.bottom);
        Dimension character = getCharUnitSize();
        result.width /= character.width;
        result.height /= character.height;
        return result;
    }
    
    // Methods used by TerminalModel in order to update the display.
    
    public void linesChangedFrom(int lineIndex) {
        Point redrawTop = modelToView(new Location(lineIndex, 0)).getLocation();
        redoHighlightsFrom(lineIndex);
        Dimension size = getSize();
        repaint(redrawTop.x, redrawTop.y, size.width, size.height - redrawTop.y);
    }
    
    public void sizeChanged() {
        Dimension size = getOptimalViewSize();
        setMaximumSize(size);
        setPreferredSize(size);
        setSize(size);
        revalidate();
    }
    
    public void sizeChanged(Dimension oldSizeInChars, Dimension newSizeInChars) {
        sizeChanged();
        redoHighlightsFrom(Math.min(oldSizeInChars.height, newSizeInChars.height));
    }
    
    public void scrollToBottomButNotHorizontally() {
        JScrollPane pane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, this);
        
        BoundedRangeModel verticalModel = pane.getVerticalScrollBar().getModel();
        verticalModel.setValue(verticalModel.getMaximum() - verticalModel.getExtent());
    }
    
    public void scrollToEnd() {
        scrollToBottomButNotHorizontally();
        scrollHorizontallyToShowCursor();
    }
    
    private boolean isLineVisible(int lineIndex) {
        return (lineIndex >= getFirstVisibleLine() && lineIndex <= getLastVisibleLine());
    }
    
    public void scrollHorizontallyToShowCursor() {
        JScrollPane pane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, this);
        
        if (isLineVisible(getCursorPosition().getLineIndex()) == false) {
            // We shouldn't be jumping the horizontal scroll bar
            // about because of new output if the user's trying to
            // review the history.
            return;
        }
        
        // mutt(1) likes to leave the cursor one character off the right of the bottom line.
        if (displayCursor == false) {
            return;
        }
        
        // FIXME: we don't necessarily have a horizontal position that
        // corresponds to where the cursor is. This is probably a
        // mistake that should be fixed.
        
        // [To reproduce the problem underlying this code, simply
        // "cat > /dev/null" and then type more characters than fit
        // on a line.]
        
        int leftCursorEdge = getCursorPosition().getCharOffset() * getCharUnitSize().width;
        int rightCursorEdge = leftCursorEdge + getCharUnitSize().width;
        
        BoundedRangeModel horizontalModel = pane.getHorizontalScrollBar().getModel();
        
        int leftWindowEdge = horizontalModel.getValue();
        int rightWindowEdge = leftWindowEdge + horizontalModel.getExtent();
        
        // We don't want to scroll back as the user moves the
        // cursor back; we should just ensure that the cursor
        // is visible, and do nothing if it is already visible.
        if (leftCursorEdge < leftWindowEdge) {
            horizontalModel.setValue(leftCursorEdge - horizontalModel.getExtent() / 2);
        } else if (rightCursorEdge > rightWindowEdge) {
            horizontalModel.setValue(rightCursorEdge - horizontalModel.getExtent() / 2);
        }
    }
    
    public void scrollToTop() {
        scrollTo(0, 0, 0);
    }
    
    private void scrollTo(final int lineNumber, final int charStart, final int charEnd) {
        Dimension character = getCharUnitSize();
        final int x0 = charStart * character.width;
        final int y0 = lineNumber * character.height - 10;
        final int width = (charEnd - charStart) * character.width;
        final int height = character.height + 20;
        // Showing the beginning of the line first lets us scroll
        // horizontally as far as necessary but no further. We'd rather
        // show more of the beginning of the line in case we've jumped
        // here from a long way away; the beginning is where the
        // context is.
        scrollRectToVisible(new Rectangle(0, y0, 0, height));
        scrollRectToVisible(new Rectangle(x0, y0, width, height));
    }
    
    /**
     * Scrolls to the bottom of the output if doing so fits the user's
     * configuration, or is over-ridden by the fact that we're trying to
     * stay where we were but that *was* the bottom.
     */
    public void scrollOnTtyOutput(boolean wereAtBottom) {
        if (wereAtBottom || Terminator.getPreferences().getBoolean(TerminatorPreferences.SCROLL_ON_TTY_OUTPUT)) {
            scrollToBottomButNotHorizontally();
        }
    }
    
    /**
     * Tests whether we're currently at the bottom of the output. Code
     * that's causing output will need to keep the result of invoking this
     * method so it can invoke scrollOnTtyOutput correctly afterwards.
     */
    public boolean isAtBottom() {
        Rectangle visibleRectangle = getVisibleRect();
        boolean atBottom = visibleRectangle.y + visibleRectangle.height >= getHeight();
        return atBottom;
    }
    
    public Location getCursorPosition() {
        return cursorPosition;
    }
    
    public void setCursorPosition(Location newCursorPosition) {
        if (cursorPosition.equals(newCursorPosition)) {
            return;
        }
        redrawCursorPosition();
        cursorPosition = newCursorPosition;
        redrawCursorPosition();
    }
    
    /** Sets whether the cursor should be displayed. */
    public void setCursorVisible(boolean displayCursor) {
        if (this.displayCursor != displayCursor) {
            this.displayCursor = displayCursor;
            redrawCursorPosition();
        }
    }
    
    public boolean shouldShowCursor() {
        return displayCursor;
    }
    
    public Color getCursorColor() {
        // Is this ever called when !blinkOn?
        return Terminator.getPreferences().getColor(blinkOn ? TerminatorPreferences.CURSOR_COLOR : TerminatorPreferences.BACKGROUND_COLOR);
    }
    
    public void blinkCursor() {
        blinkOn = !blinkOn;
        redrawCursorPosition();
    }
    
    public Location viewToModel(Point point) {
        return viewToModel(point, false);
    }
    
    public Location viewToModel(Point point, boolean blockMode) {
        Insets insets = getInsets();
        int lineIndex = (point.y - insets.top) / getCharUnitSize().height;
        String modelLine = "";
        // In line mode, if the line index is off the top or bottom, we leave charOffset = 0.
        // This makes it easier to select the whole first or last line.
        if (lineIndex >= model.getLineCount()) {
            lineIndex = model.getLineCount();
        } else if (lineIndex < 0) {
            lineIndex = 0;
        } else {
            modelLine = model.getTextLine(lineIndex).getString();
        }
        // In block mode, there may not be text at the point we want to calculate.
        // We assume that W (see getCharUnitSize) doesn't have zero width.
        // This does create very large strings & cause some GC but the measured
        // performance impact is not prohibitive and only felt during
        // block selection.  See:
        // http://groups.google.com/group/terminator-users/browse_thread/thread/a71988c145e39cbb
        String padding = blockMode ? StringUtilities.nCopies(Math.max(0, point.x - insets.left), 'W') : "";
        String paddedModelLine = modelLine + padding;
        char[] chars = paddedModelLine.toCharArray();
        int charOffset = 0;
        if (chars.length > 0) {
            charOffset = GuiUtilities.getCharOffset(getFontMetrics(getFont()), 0, point.x - insets.left, chars);
        }
        return new Location(lineIndex, charOffset);
    }
    
    public Rectangle modelToView(Location charCoords) {
        // We can be asked the view rectangle of locations that are past the bottom of the text in various circumstances. Examples:
        // 1. If the user sweeps a selection too far.
        // 2. If the user starts a new shell, types "man bash", and then clears the history; we move the cursor, and want to know the old cursor location to remove the cursor from, even though there's no longer any text there.
        // Rather than have special case code in each caller, simply return a reasonable result.
        // Note that it's okay to have the empty string as the default here because we'll pad if necessary later in this method.
        String line = "";
        if (charCoords.getLineIndex() < model.getLineCount()) {
            line = model.getTextLine(charCoords.getLineIndex()).getString();
        }
        
        final int offset = Math.max(0, charCoords.getCharOffset());
        
        String characterAtLocation;
        if (line.length() == offset) {
            // A very common case is where the location is one past the end of the line.
            // We don't need to add a single space if we're just going to  pull it off again.
            // This might not seem like much, but it can be costly if you've got very long lines.
            characterAtLocation = " ";
        } else {
            // Pad the line if we need to.
            final int desiredLength = offset + 1;
            if (line.length() < desiredLength) {
                final int charactersOfPaddingRequired = desiredLength - line.length();
                line += StringUtilities.nCopies(charactersOfPaddingRequired, " ");
            }
            characterAtLocation = line.substring(offset, offset + 1);
        }
        
        String lineBeforeOffset = line.substring(0, offset);
        FontMetrics fontMetrics = getFontMetrics(getFont());
        Insets insets = getInsets();
        final int x = insets.left + fontMetrics.stringWidth(lineBeforeOffset);
        final int width = fontMetrics.stringWidth(characterAtLocation);
        final int height = getCharUnitSize().height;
        final int y = insets.top + charCoords.getLineIndex() * height;
        return new Rectangle(x, y, width, height);
    }
    
    public Dimension getOptimalViewSize() {
        Dimension character = getCharUnitSize();
        Insets insets = getInsets();
        // FIXME: really, we need to track the maximum pixel width.
        final int width = insets.left + model.getMaxLineWidth() * character.width + insets.right;
        final int height = insets.top + model.getLineCount() * character.height + insets.bottom;
        return new Dimension(width, height);
    }
    
    public void setUrlMatches(int lineIndex, Range[] matches) {
        resizeAndSet(urlMatches, lineIndex, matches);
        if (lineIndex == mouseLocation.getLineIndex()) {
            urlUnderMouse = getUrlForLocation(mouseLocation);
        }
    }
    
    public void setFindMatches(int lineIndex, Range[] matches) {
        resizeAndSet(findMatches, lineIndex, matches);
        birdView.addMatchingLine(lineIndex);
    }
    
    private static <T> void resizeAndSet(ArrayList<T> list, int index, T element) {
        if (list.size() <= index) {
            list.ensureCapacity(index);
            for (int i = list.size(); i <= index; ++i) {
                list.add(null);
            }
        }
        list.set(index, element);
    }
    
    public void removeFindMatches() {
        findMatches.clear();
        birdView.clearMatchingLines();
        repaint();
    }
    
    private void redoHighlightsFrom(int firstLineIndex) {
        removeHighlightsFrom(firstLineIndex);
        urlHighlighter.addHighlightsFrom(this, firstLineIndex);
        findHighlighter.addHighlightsFrom(this, firstLineIndex);
    }
    
    public void removeHighlightsFrom(int firstLineIndex) {
        if (firstLineIndex == 0) {
            urlUnderMouse = null;
            urlMatches.clear();
            findMatches.clear();
            birdView.clearMatchingLines();
            repaint();
            return;
        }
        
        birdView.setValueIsAdjusting(true);
        try {
            for (int i = urlMatches.size() - 1; i >= firstLineIndex; --i) {
                urlMatches.remove(i);
            }
            // We use a backwards loop because going forwards results in N array copies if we're removing N lines.
            for (int i = findMatches.size() - 1; i >= firstLineIndex; --i) {
                findMatches.remove(i);
                birdView.removeMatchingLine(i);
            }
            repaintFromLine(firstLineIndex);
        } finally {
            birdView.setValueIsAdjusting(false);
        }
    }
    
    public BirdView getBirdView() {
        return birdView;
    }
    
    private void repaintFromLine(int firstLineToRepaint) {
        int top = modelToView(new Location(firstLineToRepaint, 0)).y;
        Dimension size = getSize();
        repaint(0, top, size.width, size.height - top);
    }
    
    private void repaintLine(int index) {
        FontMetrics metrics = getFontMetrics(getFont());
        int h = getCharUnitSize().height;
        int y = getInsets().top + index * h;
        repaint(0, y, getSize().width, h);
    }
    
    /**
     * Searches from startLine to endLine inclusive, incrementing the
     * current line by 'direction', looking for a line with a find highlight.
     * When one is found, we scroll there.
     */
    private void findAgain(int startLine, int endLine, int direction) {
        if (direction != 1 && direction != -1) {
            throw new IllegalArgumentException("Invalid direction: " + direction);
        }
        for (int i = startLine; i != endLine; i += direction) {
            Range[] matches = matchesForLine(i);
            if (matches != null) {
                scrollTo(i, matches[0].getStart(), matches[0].getEnd());
                birdsEye.setCurrentLineIndex(i);
                // Highlight the new match in the bird view as well as in the text itself.
                birdView.repaint();
                return;
            }
        }
    }
    
    private Range[] matchesForLine(int i) {
        return i >= findMatches.size() ? null : findMatches.get(i);
    }
    
    /**
     * Scrolls the display down to the next highlight of the given class not currently on the display.
     */
    public void findNext() {
        findAgain(getLastVisibleLine() + 1, getModel().getLineCount() + 1, 1);
    }
    
    /**
     * Scrolls the display up to the next highlight of the given class not currently on the display.
     */
    public void findPrevious() {
        findAgain(getFirstVisibleLine() - 1, -1, -1);
    }
    
    public JViewport getViewport() {
        return (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
    }
    
    public int getFirstVisibleLine() {
        int lineHeight = getCharUnitSize().height;
        Rectangle visibleBounds = getViewport().getViewRect();
        return visibleBounds.y / lineHeight;
    }
    
    public int getLastVisibleLine() {
        int lineHeight = getCharUnitSize().height;
        Rectangle visibleBounds = getViewport().getViewRect();
        return (visibleBounds.y + visibleBounds.height) / lineHeight;
    }

    private Range getUrlForLocation(Location location) {
        int line = location.getLineIndex();
        int offset = location.getCharOffset();
        if (line >= urlMatches.size() || urlMatches.get(line) == null) {
            return null;
        }
        for (Range r : urlMatches.get(line)) {
            // Optimization: URLs are in order.
            if (r.getStart() > offset) {
                return null;
            }
            if (r.getStart() <= offset && offset < r.getEnd()) {
                return r;
            }
        }
        return null;
    }
    
    private int getLineStart(boolean blockMode, Location start, Location end, int lineIndex) {
        int startOffset = start.getCharOffset();
        int endOffset = end.getCharOffset();
        boolean isFirstLine = lineIndex == start.getLineIndex();
        if (blockMode) {
            return Math.min(startOffset, endOffset);
        }
        if (isFirstLine) {
            return startOffset;
        }
        return 0;
    }
    
    private int getLineEnd(boolean blockMode, Location start, Location end, int lineIndex) {
        int startOffset = start.getCharOffset();
        int endOffset = end.getCharOffset();
        boolean isLastLine = lineIndex == end.getLineIndex();
        if (blockMode) {
            return Math.max(startOffset, endOffset);
        }
        if (isLastLine) {
            return endOffset;
        }
        TextLine textLine = model.getTextLine(lineIndex);
        int lineLength = textLine.length();
        return lineLength;
    }
    
    public String getTabbedString(Location start, Location end, boolean blockMode) {
        StringBuilder buf = new StringBuilder();
        for (int i = start.getLineIndex(); i <= end.getLineIndex(); i++) {
            // Necessary to cope with selections extending to the bottom of the buffer.
            if (i == end.getLineIndex() && end.getCharOffset() == 0) {
                break;
            }
            TextLine textLine = model.getTextLine(i);
            // In block mode, even the start of the selection may be beyond the end of the model line.
            int lineStart = Math.min(textLine.length(), getLineStart(blockMode, start, end, i));
            int lineEnd = Math.min(textLine.length(), getLineEnd(blockMode, start, end, i));
            buf.append(textLine.getTabbedString(lineStart, lineEnd));
            if (i != end.getLineIndex()) {
                buf.append('\n');
            }
        }
        return buf.toString();
    }
    
    // Redraw code.
    private void redrawCursorPosition() {
        Rectangle cursorRect = modelToView(cursorPosition);
        repaint(cursorRect);
    }
    
    // FIXME: why isn't the single caller of this just a nested Math.max(...Math.min()) ?
    private static int getMinGT(int bound, int... args) {
        int out = Integer.MAX_VALUE;
        for (int v : args) {
            if (v < out && v > bound) {
                out = v;
            }
        }
        return out;
    }

    @Override public void paintComponent(Graphics oldGraphics) {
        Stopwatch.Timer timer = paintComponentStopwatch.start();
        try {
            Graphics2D g = (Graphics2D) oldGraphics;
            GuiUtilities.setTextAntiAliasing(g, Terminator.getPreferences().getBoolean(TerminatorPreferences.ANTI_ALIAS));
            
            FontMetrics metrics = getFontMetrics(getFont());
            Dimension charUnitSize = getCharUnitSize();
            
            Rectangle rect = g.getClipBounds();
            g.setColor(getBackground());
            g.fill(rect);
            
            // We manually "clip" for performance, but we're quite loose about it.
            // This avoids accidental pathological cases (hopefully) and doesn't seem to have any significant cost.
            final int maxX = rect.x + rect.width;
            final int widthHintInChars = maxX / charUnitSize.width * 2;
            
            Insets insets = getInsets();
            int firstTextLine = (rect.y - insets.top) / charUnitSize.height;
            int lastTextLine = (rect.y - insets.top + rect.height + charUnitSize.height - 1) / charUnitSize.height;
            lastTextLine = Math.min(lastTextLine, model.getLineCount() - 1);
            final Location selectionStart = selectionHighlighter.getStart();
            final Location selectionEnd = selectionHighlighter.getEnd();
            final boolean hasSelection = selectionStart != null;

            for (int i = firstTextLine; i <= lastTextLine; i++) {
                boolean drawCursor = (shouldShowCursor() && i == cursorPosition.getLineIndex());
                int x = insets.left;
                int baseline = insets.top + charUnitSize.height * (i + 1) - metrics.getMaxDescent();
                TextLine textLine = model.getTextLine(i);
                final int length = textLine.length();
                int urlStart = length;
                int urlEnd = length;
                if (urlUnderMouse != null && i == mouseLocation.getLineIndex()) {
                    urlStart = urlUnderMouse.getStart();
                    urlEnd = urlUnderMouse.getEnd();
                }
                Range[] findResults = matchesForLine(i);
                int findIndex = -1;
                int findStart = 0, findEnd = -1;
                for (int start = 0, end, done; start < length && x < maxX; start = done) {
                    if (findResults != null && findEnd <= start && ++findIndex < findResults.length) {
                        findStart = findResults[findIndex].getStart();
                        findEnd = findResults[findIndex].getEnd();
                    }
                    end = getMinGT(start, findStart, findEnd, urlStart, urlEnd, length);
                    done = textLine.getRunLimit(start, end);
                    String text = textLine.getSubstring(start, done);
                    Style style = textLine.getStyleAt(start);
                    boolean isUrl = urlStart <= start && start < urlEnd;
                    boolean isFind = findStart <= start && start < findEnd;
                    x += paintStyledText(g, metrics, text, style, x, baseline, isUrl, isFind);
                    if (drawCursor && cursorPosition.charOffsetInRange(start, done)) {
                        paintCursor(g, textLine.getSubstring(cursorPosition.getCharOffset(), cursorPosition.getCharOffset() + 1), baseline);
                        drawCursor = false;
                    }
                }
                Color lineBG = textLine.getBackground();
                if (x < maxX && !getBackground().equals(lineBG)) {
                    // Fill the rest of the line with line's default background
                    g.setColor(lineBG);
                    g.fillRect(x, baseline - metrics.getMaxAscent() - metrics.getLeading(), maxX - x, charUnitSize.height);
                }
                if (drawCursor) {
                    // A cursor at the end of the line is in a position past the end of the text.
                    paintCursor(g, "", baseline);
                }
                if (hasSelection && selectionStart.getLineIndex() <= i && i <= selectionEnd.getLineIndex()) {
                    boolean blockMode = selectionHighlighter.isBlockMode();
                    int start = getLineStart(blockMode, selectionStart, selectionEnd, i);
                    int end = getLineEnd(blockMode, selectionStart, selectionEnd, i);
                    boolean toEnd = blockMode == false && selectionEnd.getLineIndex() != i;
                    String paddedLine = textLine.getString();
                    if (end > length) {
                        final int charactersOfPaddingRequired = end - length;
                        // See getCharUnitSize for the 'W'.
                        paddedLine += StringUtilities.nCopies(charactersOfPaddingRequired, 'W');
                    }

                    // FIXME: this is likely to want some tuning; in particular, we might need to distinguish between light-on-dark and dark-on-light color schemes.
                    Color selectionColor = Terminator.getPreferences().getColor(TerminatorPreferences.SELECTION_COLOR);
                    g.setColor(new Color(selectionColor.getRed(), selectionColor.getGreen(), selectionColor.getBlue(), 128));

                    x = insets.left + (start == 0 ? 0 : metrics.stringWidth(paddedLine.substring(0, start)));
                    int y = baseline - metrics.getMaxAscent() - metrics.getLeading();
                    int w = toEnd ? maxX - x : metrics.stringWidth(paddedLine.substring(start, end));
                    int h = charUnitSize.height;

                    g.fillRect(x, y, w, h);
                }
            }
        } finally {
            timer.stop();
        }
    }
    
    /**
     * Paints the cursor, which is either a solid block or an underline.
     * The cursor may actually be invisible because it's blinking and in
     * the 'off' state.
     */
    private void paintCursor(Graphics2D g, String characterUnderCursor, int baseline) {
        g.setColor(getCursorColor());
        Rectangle cursorRect = modelToView(cursorPosition);
        final int bottomY = cursorRect.y + cursorRect.height - 1;
        if (isFocusOwner()) {
            TerminatorPreferences preferences = Terminator.getPreferences();
            // The CursorBlinker may have left blinkOn in either state if the user changed the cursor blink preference.
            // Ignore blinkOn if the cursor shouldn't be blinking right now.
            boolean cursorIsVisible = (preferences.getBoolean(TerminatorPreferences.BLINK_CURSOR) == false) || blinkOn;
            if (preferences.getBoolean(TerminatorPreferences.BLOCK_CURSOR)) {
                // Block.
                if (cursorIsVisible) {
                    // Paint over the character underneath.
                    g.fill(cursorRect);
                    // Redraw the character in the
                    // background color.
                    g.setColor(getBackground());
                    g.drawString(characterUnderCursor, cursorRect.x, baseline);
                }
            } else {
                // Underline.
                if (cursorIsVisible) {
                    g.drawLine(cursorRect.x, bottomY, cursorRect.x + cursorRect.width - 1, bottomY);
                }
            }
        } else {
            // For some reason, terminals always seem to use an
            // empty block for the unfocused cursor, regardless
            // of what shape they're using for the focused cursor.
            // It's not obvious what else they could do that would
            // look better.
            g.drawRect(cursorRect.x, cursorRect.y, cursorRect.width - 1, cursorRect.height - 1);
        }
    }
    
    /**
     * Paints the text. Returns how many pixels wide the text was.
     */
    private int paintStyledText(Graphics2D g, FontMetrics metrics, String text, Style style, int x, int y, boolean url, boolean find) {
        Stopwatch.Timer timer = paintStyledTextStopwatch.start();
        try {
            Color foreground = find ? Color.BLACK : style.getForeground();
            Color background = find ? Color.YELLOW : style.getBackground();
            
            if (style.isReverseVideo()) {
                Color oldForeground = foreground;
                foreground = background;
                background = oldForeground;
            }
            
            int textWidth = metrics.stringWidth(text);
            if (background.equals(getBackground()) == false) {
                g.setColor(background);
                int backgroundWidth = textWidth;
                g.fillRect(x, y - metrics.getMaxAscent() - metrics.getLeading(), backgroundWidth, metrics.getHeight());
            }
            if (url || style.isUnderlined()) {
                g.setColor(new Color(foreground.getRed(), foreground.getGreen(), foreground.getBlue(), 128));
                g.drawLine(x, y + 1, x + textWidth, y + 1);
            }
            g.setColor(foreground);
            g.drawString(text, x, y);
            if (style.isBold()) {
                // A font doesn't necessarily have a bold.
                // Mac OS X's "Monaco" font is an example.
                // The trouble is, you can't tell from the Font you get back from deriveFont.
                // isBold will always return true, and getting the WEIGHT attribute will give you WEIGHT_BOLD.
                // So we don't know how to test for a bold font.
                
                // Worse, if we actually get a bold font, it doesn't necessarily have metrics compatible with the plain variant.
                // ProggySquare (http://www.proggyfonts.com/) is an example: the bold variant is significantly wider.
                
                // The old-fashioned "overstrike" method of faking bold doesn't look too bad, and it works in these awkward cases.
                g.drawString(text, x + 1, y);
            }
            return textWidth;
        } finally {
            timer.stop();
        }
    }
    
    //
    // FocusListener interface.
    //
    
    public void focusGained(FocusEvent event) {
        blinkOn = true;
        cursorBlinker.start();
        redrawCursorPosition();
    }
    
    public void focusLost(FocusEvent event) {
        blinkOn = true;
        cursorBlinker.stop();
        redrawCursorPosition();
    }
    
    //
    // Scrollable interface.
    //
    
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }
    
    public int getScrollableUnitIncrement(Rectangle visibleRectangle, int orientation, int direction) {
        if (orientation == SwingConstants.VERTICAL) {
            return visibleRectangle.height / 10;
        } else {
            return 3 * getCharUnitSize().width;
        }
    }
    
    public int getScrollableBlockIncrement(Rectangle visibleRectangle, int orientation, int direction) {
        if (orientation == SwingConstants.VERTICAL) {
            return visibleRectangle.height;
        } else {
            return visibleRectangle.width;
        }
    }
    
    public boolean getScrollableTracksViewportWidth() {
        return false;
    }
    
    public boolean getScrollableTracksViewportHeight() {
        return false; // We want a vertical scroll-bar.
    }
}
