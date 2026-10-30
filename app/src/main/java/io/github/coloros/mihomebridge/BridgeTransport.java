package io.github.coloros.mihomebridge;

import android.os.Parcel;
import android.os.ResultReceiver;

/** Send a framework parcelable, so an older host never needs a new anonymous callback class. */
final class BridgeTransport {
    static ResultReceiver remoteReply(ResultReceiver local) {
        Parcel parcel = Parcel.obtain();
        try {
            local.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return ResultReceiver.CREATOR.createFromParcel(parcel);
        } finally { parcel.recycle(); }
    }
    private BridgeTransport() {}
}
