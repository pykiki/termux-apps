package com.termux.app.api;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.util.Log;

import androidx.annotation.Nullable;

import com.termux.app.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Hands a document to Android's print service. PrintManager.print accepts no
 * context that is not an Activity - it throws "Can print only from an
 * activity" - so the call is made from a transparent one, as DialogAPI and
 * StorageGetAPI already do. The file is spooled byte for byte: a caller
 * holding a PDF should not have to render it again to reach a printer.
 */
public class PrintAPI {

    private static final String FILE_EXTRA = "com.termux.print.file";
    private static final String NAME_EXTRA = "com.termux.print.name";

    public static void onReceive(final Context context, final Intent intent) {
        ResultReturner.returnData(intent, out -> {
            var fileExtra = intent.getStringExtra("file");
            if (fileExtra == null || fileExtra.isEmpty()) {
                out.println("ERROR: File path not passed");
                return;
            }
            var document = new File(fileExtra);
            // Checked here, where the answer can still be reported: the print
            // UI opens on its own task and an unreadable file only shows up
            // there as a job that failed for no stated reason.
            if (!document.isFile() || !document.canRead()) {
                out.println("ERROR: Cannot read " + fileExtra);
                return;
            }
            var name = intent.getStringExtra("name");
            context.startActivity(new Intent(context, PrintActivity.class)
                .putExtra(FILE_EXTRA, document.getAbsolutePath())
                .putExtra(NAME_EXTRA, (name == null || name.isEmpty()) ? document.getName() : name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
    }

    public static class PrintActivity extends Activity {

        @Override
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
        }

        @Override
        public void onResume() {
            super.onResume();
            var file = new File(getIntent().getStringExtra(FILE_EXTRA));
            var name = getIntent().getStringExtra(NAME_EXTRA);
            var printManager = getSystemService(PrintManager.class);
            printManager.print(name, new DocumentAdapter(file, name),
                new PrintAttributes.Builder().build());
            // The spooler holds the adapter over IPC and draws its own UI from
            // here on, so this window has nothing left to show.
            finish();
        }
    }

    /**
     * Copies the document to the spooler unchanged. The page count needs the
     * file parsed to be known, and PAGE_COUNT_UNKNOWN is what the print
     * framework offers for exactly that case.
     */
    private static class DocumentAdapter extends PrintDocumentAdapter {

        private final File file;
        private final String name;

        DocumentAdapter(File file, String name) {
            this.file = file;
            this.name = name;
        }

        @Override
        public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                             CancellationSignal cancellationSignal, LayoutResultCallback callback,
                             Bundle extras) {
            if (cancellationSignal != null && cancellationSignal.isCanceled()) {
                callback.onLayoutCancelled();
                return;
            }
            var info = new PrintDocumentInfo.Builder(name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build();
            callback.onLayoutFinished(info, !newAttributes.equals(oldAttributes));
        }

        @Override
        public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
                            CancellationSignal cancellationSignal, WriteResultCallback callback) {
            try (InputStream in = new FileInputStream(file);
                 OutputStream out = new FileOutputStream(destination.getFileDescriptor())) {
                var buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    if (cancellationSignal != null && cancellationSignal.isCanceled()) {
                        callback.onWriteCancelled();
                        return;
                    }
                    out.write(buffer, 0, read);
                }
                callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
            } catch (IOException e) {
                Log.e(TermuxConstants.LOG_TAG, "PrintAPI: cannot spool " + file, e);
                callback.onWriteFailed(e.getMessage());
            }
        }
    }
}
