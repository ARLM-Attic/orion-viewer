package com.google.code.orion_viewer;

/*Orion Viewer is a pdf viewer for Nook Classic based on mupdf

Copyright (C) 2011  Michael Bogdanov

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.*/

import android.content.Context;
import android.graphics.*;
import android.os.Debug;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.concurrent.*;

/**
 * User: mike
 * Date: 19.10.11
 * Time: 9:52
 */
public class RenderThread extends Thread {

    private LayoutStrategy layout;

    private LinkedList<CacheInfo> cachedBitmaps = new LinkedList<CacheInfo>();

    private OrionView view;

    private LayoutPosition currentPosition;

    private LayoutPosition lastEvent;

    private DocumentWrapper doc;

    private int CACHE_SIZE = 4;

    private int FUTURE_COUNT = 1;

    private Canvas cacheCanvas = new Canvas();

    private Bitmap.Config bitmapConfig;

    private boolean clearCache;

    private int rotationShift;

    private boolean stopped;

    private boolean paused;

    private OrionViewerActivity activity;


    public RenderThread(OrionViewerActivity activity, OrionView view, LayoutStrategy layout, DocumentWrapper doc) {
        this.view = view;
        this.layout = layout;
        this.doc = doc;
        rotationShift = (layout.getViewHeight() - layout.getViewWidth()) / 2;
        this.activity = activity;

        WindowManager manager = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
        if (manager == null) {
            bitmapConfig = Bitmap.Config.ARGB_8888;
            return;
        }

        DisplayMetrics metrics = new DisplayMetrics();
        manager.getDefaultDisplay().getMetrics(metrics);

        switch (manager.getDefaultDisplay().getPixelFormat()) {
            case PixelFormat.A_8:
                bitmapConfig = Bitmap.Config.ALPHA_8;
                break;
            case PixelFormat.RGB_565:
                bitmapConfig = Bitmap.Config.RGB_565;
                break;
            case PixelFormat.RGBA_4444:
                bitmapConfig = Bitmap.Config.ARGB_4444;
                break;
            case PixelFormat.RGBA_8888:
                bitmapConfig = Bitmap.Config.ARGB_8888;
                break;
        }

        Common.d("PixelFormat is " +  manager.getDefaultDisplay().getPixelFormat());
    }

    public void invalidateCache() {
        synchronized (this) {
            for (Iterator<CacheInfo> iterator = cachedBitmaps.iterator(); iterator.hasNext(); ) {
                CacheInfo next = iterator.next();
                next.setValid(false);
            }
            Common.d("Cache invalidated");
        }
    }


    public void cleanCache() {
        synchronized (this) {
            //if(clearCache) {
              //  clearCache = false;
                for (Iterator<CacheInfo> iterator = cachedBitmaps.iterator(); iterator.hasNext(); ) {
                    CacheInfo next = iterator.next();
                    next.bitmap.recycle();
                    next.bitmap = null;
                }

                Common.d("Allocated heap size " + (Debug.getNativeHeapAllocatedSize() - Debug.getNativeHeapFreeSize()));
                cachedBitmaps.clear();
                Common.d("Cache is cleared!");
            //}


            currentPosition = null;
            //clearCache = true;
        }
    }

    public void stopRenderer() {
        synchronized (this) {
            stopped = true;
            cleanCache();
            notify();
        }
    }

    public void onPause() {
//        synchronized (this) {
//            paused = true;
//        }
    }

    public void onResume() {
        synchronized (this) {
            paused = false;
            notify();
        }
    }

    public void run() {
        int futureIndex = 0;
        LayoutPosition curPos = null;

        while (!stopped) {

            Common.d("Allocated heap size " + (Debug.getNativeHeapAllocatedSize() - Debug.getNativeHeapFreeSize()));

            int rotation = 0;
            CacheInfo resultEntry = null;
            synchronized (this) {
                if (paused) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Common.d(e);
                    }
                    continue;
                }

                if (lastEvent != null) {
                    currentPosition = lastEvent;
                    lastEvent = null;
                    futureIndex = 0;
                    curPos = currentPosition;
                }

                //keep it here
                rotation = layout.getRotation();

                if (currentPosition == null || futureIndex > FUTURE_COUNT) {
                    try {
                        Common.d("WAITING...");
                        wait();
                    } catch (InterruptedException e) {
                        Common.d(e);
                    }
                    Common.d("AWAKENING!!!");
                    continue;
                } else {
                    //will cache next page
                    Common.d("Future index is " + futureIndex);
                    if (futureIndex != 0) {
                        curPos = curPos.clone();
                        layout.nextPage(curPos);
                    }
                }
            }

            if (curPos != null) {
                //try to find result in cache
                for (Iterator<CacheInfo> iterator = cachedBitmaps.iterator(); iterator.hasNext(); ) {
                    CacheInfo cacheInfo =  iterator.next();
                    if (cacheInfo.isValid() && cacheInfo.info.equals(curPos)) {
                        resultEntry = cacheInfo;
                        //relocate info to end of cache
                        iterator.remove();
                        cachedBitmaps.add(cacheInfo);
                        break;
                    }
                }


                if (resultEntry == null) {
                    //render page
                    int width = curPos.pieceWidth;
                    int height = curPos.pieceHeight;

                    Bitmap bitmap = null;
                    if (cachedBitmaps.size() >= CACHE_SIZE) {
                        CacheInfo info = cachedBitmaps.removeFirst();
                        info.setValid(true);

                        if (width == info.bitmap.getWidth() && height == info.bitmap.getHeight() || rotation != 0 && width == info.bitmap.getHeight() && height == info.bitmap.getWidth()) {
                            bitmap = info.bitmap;
                        } else {
                            info.bitmap.recycle(); //todo recycle from ui
                            info.bitmap = null;
                        }
                    }
                    if (bitmap == null) {
//                        try {
                            Common.d("CREATING BITMAP!!!");
                            bitmap = Bitmap.createBitmap(layout.getViewWidth(), layout.getViewHeight(), bitmapConfig);
//                        } catch (OutOfMemoryError e) {
//                            System.gc();
//                        }
                    }

                    cacheCanvas.setMatrix(null);
                    if (rotation != 0) {
                        cacheCanvas.rotate(-rotation * 90, (view.getHeight()) / 2, view.getWidth() / 2);
                        cacheCanvas.translate(-rotation * rotationShift, -rotation * rotationShift);
                    }

                    Point leftTopCorner = layout.convertToPoint(curPos);
                    Common.d("Point " + leftTopCorner.x + " " + leftTopCorner.y);
                    int [] data = doc.renderPage(curPos.pageNumber, curPos.docZoom, width, height, leftTopCorner.x, leftTopCorner.y, leftTopCorner.x + width, leftTopCorner.y + height);

                    Date date = new Date();
                    cacheCanvas.setBitmap(bitmap);

//                    Paint p = new Paint();
//                    ColorFilter filter = new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.MULTIPLY);
//                    p.setColorFilter(filter);
//                     p.setRasterizer()

                    cacheCanvas.drawBitmap(data, 0, width, 0, 0, width, height, true, null);
                    Date date2 = new Date();
                    Common.d("Drawing bitmap in cache " + 0.001 * (date2.getTime() - date.getTime()) + " s");

                    resultEntry = new CacheInfo(curPos, bitmap);

                    cachedBitmaps.add(resultEntry);
                }
            }

            if (futureIndex == 0) {
                final Bitmap bitmap = resultEntry.bitmap;
                Common.d("Sending Bitmap");
                final CountDownLatch  mutex = new CountDownLatch(1);

                activity.runOnUiThread(new Runnable() {
                    public void run() {
                        view.setData(bitmap, mutex);
                        //view.invalidate();
                        activity.getDevice().flush();
                    }
                });

                try {
                    mutex.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Common.d(e);
                }
            }
            futureIndex++;
        }
    }

    public void render(LayoutPosition lastInfo) {
        lastInfo = lastInfo.clone();
        synchronized (this) {
            lastEvent = lastInfo;
            notify();
        }
    }

    static class CacheInfo {

        public CacheInfo(LayoutPosition info, Bitmap bitmap) {
            this.info  = info;
            this.bitmap = bitmap;
        }

        private LayoutPosition info;
        private Bitmap bitmap;

        private boolean valid = true;

        public LayoutPosition getInfo() {
            return info;
        }

        public Bitmap getBitmap() {
            return bitmap;
        }

        public boolean isValid() {
            return valid;
        }

        public void setValid(boolean valid) {
            this.valid = valid;
        }
    }
}
