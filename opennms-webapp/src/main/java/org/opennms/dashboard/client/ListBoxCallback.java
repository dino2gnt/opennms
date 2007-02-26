/**
 * 
 */
package org.opennms.dashboard.client;

import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.ListBox;

public class ListBoxCallback implements AsyncCallback {
    private Dashlet m_dashlet;
    private int m_direction = 1;
    private ListBox m_listBox;
    private String m_emptyListItemName = null;
    private String m_emptyListItemValue;
    
    public ListBoxCallback(Dashlet dashlet, ListBox listBox) {
        m_dashlet = dashlet;
        m_listBox = listBox;
    }
    
    public ListBox getListBox() {
        return m_listBox;
    }

    public void setDirection(int direction) {
        m_direction = direction;
    }
    
    public int getDirection() {
        return m_direction;
    }
    
    public void setEmptyListItem(String name, String value) {
        m_emptyListItemName = name;
        m_emptyListItemValue = value;
    }

    public void onDataLoaded(String[][] resources) {
        m_listBox.clear();
        
        if (resources.length == 0) {
            if (m_emptyListItemName != null) {
                m_listBox.addItem(m_emptyListItemName, m_emptyListItemValue);
            }
        } else {
            for (int i = 0; i < resources.length; i++) {
                m_listBox.addItem(resources[i][1], resources[i][0]);
            }
        }
        
        int itemCount = m_listBox.getItemCount();
        if (m_direction < 0 && itemCount > 0) {
            m_listBox.setSelectedIndex(itemCount - 1);
        } else if (m_direction > 0 && itemCount > 0) {
            m_listBox.setSelectedIndex(0);
        }

        m_dashlet.setStatus("");
    }
    
    public void onFailure(Throwable caught) {
        m_dashlet.setStatus("Error");
        m_dashlet.error(caught);
    }

    public void onSuccess(Object result) {
        onDataLoaded((String[][]) result);
    }

}