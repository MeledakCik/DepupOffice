package android.print;

import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Menulis {@link PrintDocumentAdapter} (mis. adaptor cetak WebView) langsung ke berkas PDF, TANPA dialog cetak
 * sistem — dipakai "Simpan sebagai PDF" DepUp Office.
 *
 * Kelas ini sengaja berada di paket {@code android.print}: konstruktor LayoutResultCallback & WriteResultCallback
 * disembunyikan dari SDK publik (hanya terlihat dari paket yang sama). Aturan R8 di proguard-rules.pro menjaga
 * kelas ini tetap di paket ini. Bila perangkat menolak cara ini, pemanggil kembali ke dialog cetak sistem.
 */
public final class DepupPdfWriter {
    /** Selesai (selalu dipanggil tepat sekali): ok = berkas PDF lengkap; error = alasan bila gagal. */
    public interface Done {
        void onDone(boolean ok, String error);
    }

    private DepupPdfWriter() {
    }

    /** Mulai menulis; kembalikan sinyal untuk membatalkan (mis. batas waktu). */
    public static CancellationSignal write(final PrintDocumentAdapter adapter, final PrintAttributes attrs,
                                           final File out, final Done done) {
        final CancellationSignal cancel = new CancellationSignal();
        final AtomicBoolean finished = new AtomicBoolean(false);
        try {
            adapter.onStart();
            adapter.onLayout(null, attrs, cancel, new PrintDocumentAdapter.LayoutResultCallback() {
                @Override
                public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                    final ParcelFileDescriptor dest;
                    try {
                        dest = ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_CREATE
                                | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_READ_WRITE);
                    } catch (Exception e) {
                        finish(adapter, finished, done, false, String.valueOf(e.getMessage()));
                        return;
                    }
                    try {
                        adapter.onWrite(new PageRange[]{PageRange.ALL_PAGES}, dest, cancel,
                                new PrintDocumentAdapter.WriteResultCallback() {
                                    @Override
                                    public void onWriteFinished(PageRange[] pages) {
                                        close(dest);
                                        finish(adapter, finished, done, true, null);
                                    }

                                    @Override
                                    public void onWriteFailed(CharSequence error) {
                                        close(dest);
                                        finish(adapter, finished, done, false, error == null ? null : error.toString());
                                    }

                                    @Override
                                    public void onWriteCancelled() {
                                        close(dest);
                                        finish(adapter, finished, done, false, "Dibatalkan");
                                    }
                                });
                    } catch (Throwable t) {
                        close(dest);
                        finish(adapter, finished, done, false, String.valueOf(t.getMessage()));
                    }
                }

                @Override
                public void onLayoutFailed(CharSequence error) {
                    finish(adapter, finished, done, false, error == null ? null : error.toString());
                }

                @Override
                public void onLayoutCancelled() {
                    finish(adapter, finished, done, false, "Dibatalkan");
                }
            }, null);
        } catch (Throwable t) {
            finish(adapter, finished, done, false, String.valueOf(t.getMessage()));
        }
        return cancel;
    }

    private static void finish(PrintDocumentAdapter adapter, AtomicBoolean finished, Done done, boolean ok, String error) {
        if (!finished.compareAndSet(false, true)) return;
        try {
            adapter.onFinish();
        } catch (Throwable ignored) {
        }
        done.onDone(ok, error);
    }

    private static void close(ParcelFileDescriptor pfd) {
        try {
            pfd.close();
        } catch (Exception ignored) {
        }
    }
}
