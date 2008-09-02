package e.edit;

import java.awt.*;
import javax.swing.*;

public class EWindow extends JComponent {
    private final ETitleBar titleBar;
    
    public EWindow(String initialTitleText) {
        setLayout(new BorderLayout());
        
        this.titleBar = new ETitleBar(initialTitleText, this);
        add(titleBar, BorderLayout.NORTH);
        
        setOpaque(true);
    }
    
    public ETitleBar getTitleBar() {
        return titleBar;
    }
    
    public String getTitle() {
        return titleBar.getTitle();
    }

    public void setTitle(String title) {
        titleBar.setTitle(title);
    }
    
    public boolean isDirty() {
        return false;
    }
    
    public ETextArea getTextArea() {
        return null;
    }
    
    /** Closes this window by removing it from its column. */
    public void closeWindow() {
        windowWillClose();
        removeFromColumn();
    }
    
    public void removeFromColumn() {
        boolean mustReassignFocus = getTitleBar().isActive();
        getColumn().removeComponent(this, mustReassignFocus);
    }
    
    /** Invoked when the window is about to be closed. */
    public void windowWillClose() {
    }
    
    public void ensureSufficientlyVisible() {
        if (getHeight() < 2 * titleBar.getHeight()) {
            expand();
        }
    }
    
    public void expand() {
        getColumn().expandComponent(this);
    }
    
    public EColumn getColumn() {
        return (EColumn) SwingUtilities.getAncestorOfClass(EColumn.class, this);
    }
    
    public Workspace getWorkspace() {
        return (Workspace) SwingUtilities.getAncestorOfClass(Workspace.class, this);
    }
}
