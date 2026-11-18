package android.print;

import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

public class PrintAdapterBridge {

    public interface LayoutHandler {
        void onLayoutFinished(PrintDocumentInfo info, boolean changed);
        void onLayoutFailed(CharSequence error);
        void onLayoutCancelled();
    }

    public interface WriteHandler {
        void onWriteFinished(PageRange[] pages);
        void onWriteFailed(CharSequence error);
        void onWriteCancelled();
    }

    public static PrintDocumentAdapter.LayoutResultCallback createLayoutCallback(final LayoutHandler handler) {
        return new PrintDocumentAdapter.LayoutResultCallback() {
            @Override
            public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                handler.onLayoutFinished(info, changed);
            }

            @Override
            public void onLayoutFailed(CharSequence error) {
                handler.onLayoutFailed(error);
            }

            @Override
            public void onLayoutCancelled() {
                handler.onLayoutCancelled();
            }
        };
    }

    public static PrintDocumentAdapter.WriteResultCallback createWriteCallback(final WriteHandler handler) {
        return new PrintDocumentAdapter.WriteResultCallback() {
            @Override
            public void onWriteFinished(PageRange[] pages) {
                handler.onWriteFinished(pages);
            }

            @Override
            public void onWriteFailed(CharSequence error) {
                handler.onWriteFailed(error);
            }

            @Override
            public void onWriteCancelled() {
                handler.onWriteCancelled();
            }
        };
    }
}
