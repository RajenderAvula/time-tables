package android.print;

import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

import java.io.File;

/** Writes a print adapter's pages straight to a PDF file, without opening the system print spooler. */
public class PdfExporter {
    public interface Done { void run(boolean ok); }

    public static void export(final PrintDocumentAdapter adapter, final File out, final Done done) {
        PrintAttributes attrs = new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape())
                .setResolution(new PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                .setMinMargins(new PrintAttributes.Margins(315, 315, 315, 315))
                .build();
        try {
            adapter.onStart();
            adapter.onLayout(null, attrs, new CancellationSignal(), new PrintDocumentAdapter.LayoutResultCallback() {
                @Override
                public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                    try {
                        final ParcelFileDescriptor fd = ParcelFileDescriptor.open(out,
                                ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
                        adapter.onWrite(new PageRange[]{PageRange.ALL_PAGES}, fd, new CancellationSignal(),
                                new PrintDocumentAdapter.WriteResultCallback() {
                                    @Override
                                    public void onWriteFinished(PageRange[] pages) {
                                        try { fd.close(); } catch (Exception ignored) { }
                                        try { adapter.onFinish(); } catch (Exception ignored) { }
                                        done.run(true);
                                    }

                                    @Override
                                    public void onWriteFailed(CharSequence error) {
                                        try { fd.close(); } catch (Exception ignored) { }
                                        done.run(false);
                                    }
                                });
                    } catch (Exception e) {
                        done.run(false);
                    }
                }

                @Override
                public void onLayoutFailed(CharSequence error) { done.run(false); }
            }, new Bundle());
        } catch (Exception e) {
            done.run(false);
        }
    }
}
