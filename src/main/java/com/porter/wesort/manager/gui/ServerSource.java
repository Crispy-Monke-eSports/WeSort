package com.porter.wesort.manager.gui;

import com.porter.wesort.manager.model.IndexModel;
import com.porter.wesort.manager.model.StashRef;
import java.util.List;

/** What the WeSort Manager server last told the window. Client thread only. */
public final class ServerSource implements DataSource {
    List<StashRef> stashes = List.of();
    IndexModel index = IndexModel.empty();

    @Override
    public List<StashRef> stashes() {
        return stashes;
    }

    @Override
    public IndexModel index() {
        return index;
    }

    @Override
    public boolean ordersSupported() {
        return true;
    }

    /** Forgets everything the server said (logout, a lost session, another account). */
    void clear() {
        stashes = List.of();
        index = IndexModel.empty();
    }
}
