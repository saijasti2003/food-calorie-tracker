package com.knowledgeshare.foodtracker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Base64;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_CAMERA = 1;
    private static final int REQ_GALLERY = 2;
    private static final int MAX_SIDE = 1024;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private String imageBase64;

    private TextView tCal, tPro, tCarb, tFat, meals, msg, items;
    private ImageView preview;
    private EditText note, food, cal, pro, carb, fat;
    private Button btnAnalyze, btnSave;
    private ProgressBar progress;
    private LinearLayout result;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);

        tCal = findViewById(R.id.tCal);
        tPro = findViewById(R.id.tPro);
        tCarb = findViewById(R.id.tCarb);
        tFat = findViewById(R.id.tFat);
        meals = findViewById(R.id.meals);
        msg = findViewById(R.id.msg);
        items = findViewById(R.id.items);
        preview = findViewById(R.id.preview);
        note = findViewById(R.id.note);
        food = findViewById(R.id.food);
        cal = findViewById(R.id.cal);
        pro = findViewById(R.id.pro);
        carb = findViewById(R.id.carb);
        fat = findViewById(R.id.fat);
        btnAnalyze = findViewById(R.id.btnAnalyze);
        btnSave = findViewById(R.id.btnSave);
        progress = findViewById(R.id.progress);
        result = findViewById(R.id.result);
        scroll = findViewById(R.id.scroll);

        findViewById(R.id.btnCamera).setOnClickListener(v -> openCamera());
        findViewById(R.id.btnGallery).setOnClickListener(v -> openGallery());
        btnAnalyze.setOnClickListener(v -> analyze());
        btnSave.setOnClickListener(v -> save());

        if (scriptUrl().isEmpty()) {
            showSettings();
        } else {
            loadToday();
        }
    }

    // ---------- Menu / settings ----------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Refresh");
        menu.add(0, 2, 1, "Settings");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) { loadToday(); return true; }
        if (item.getItemId() == 2) { showSettings(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private String scriptUrl() { return prefs.getString("url", "").trim(); }
    private String appToken() { return prefs.getString("token", "").trim(); }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(p, p / 2, p, 0);

        TextView l1 = new TextView(this);
        l1.setText("Apps Script Web App URL (ends with /exec)");
        EditText url = new EditText(this);
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(scriptUrl());
        url.setHint("https://script.google.com/macros/s/.../exec");

        TextView l2 = new TextView(this);
        l2.setText("App password (APP_TOKEN, optional)");
        l2.setPadding(0, p / 2, 0, 0);
        EditText token = new EditText(this);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(appToken());

        box.addView(l1); box.addView(url); box.addView(l2); box.addView(token);

        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    prefs.edit()
                            .putString("url", url.getText().toString().trim())
                            .putString("token", token.getText().toString().trim())
                            .apply();
                    loadToday();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------- Photo ----------

    private void openCamera() {
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        Uri out = ImageProvider.photoUri();
        i.putExtra(MediaStore.EXTRA_OUTPUT, out);
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.setClipData(android.content.ClipData.newRawUri("photo", out));
        try {
            ImageProvider.photoFile(this).delete();
            startActivityForResult(i, REQ_CAMERA);
        } catch (ActivityNotFoundException e) {
            setMsg("No camera app found. Use Gallery instead.", true);
        }
    }

    private void openGallery() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        try {
            startActivityForResult(Intent.createChooser(i, "Choose food photo"), REQ_GALLERY);
        } catch (ActivityNotFoundException e) {
            setMsg("No gallery app found.", true);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;
        Uri uri = null;
        if (requestCode == REQ_CAMERA) uri = Uri.fromFile(ImageProvider.photoFile(this));
        else if (requestCode == REQ_GALLERY && data != null) uri = data.getData();
        if (uri == null) return;

        final Uri src = uri;
        setBusy(true, "Preparing photo…");
        bg.execute(() -> {
            try {
                Bitmap bmp = loadScaled(src);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos);
                String b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
                runOnUiThread(() -> {
                    imageBase64 = b64;
                    preview.setImageBitmap(bmp);
                    preview.setVisibility(View.VISIBLE);
                    result.setVisibility(View.GONE);
                    setBusy(false, "");
                    btnAnalyze.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> { setBusy(false, ""); setMsg("Could not read photo: " + e.getMessage(), true); });
            }
        });
    }

    private InputStream open(Uri uri) throws IOException {
        if ("file".equals(uri.getScheme())) return new java.io.FileInputStream(uri.getPath());
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("Cannot open image");
        return in;
    }

    private Bitmap loadScaled(Uri uri) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = open(uri)) { BitmapFactory.decodeStream(in, null, o); }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2;

        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream in = open(uri)) { bmp = BitmapFactory.decodeStream(in, null, o2); }
        if (bmp == null) throw new IOException("Not an image");

        int rotation = 0;
        try (InputStream in = open(uri)) {
            int ori = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (ori == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
        } catch (Exception ignored) { }

        float scale = Math.min(1f, (float) MAX_SIDE / Math.max(bmp.getWidth(), bmp.getHeight()));
        if (scale < 1f || rotation != 0) {
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            m.postRotate(rotation);
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        }
        return bmp;
    }

    // ---------- Actions ----------

    private void analyze() {
        if (imageBase64 == null) return;
        if (scriptUrl().isEmpty()) { showSettings(); return; }
        btnAnalyze.setEnabled(false);
        setBusy(true, "Analyzing your food… ⏳");
        try {
            JSONObject req = new JSONObject()
                    .put("action", "analyze")
                    .put("image", imageBase64)
                    .put("mimeType", "image/jpeg")
                    .put("note", note.getText().toString().trim());
            call(req, data -> {
                JSONObject d = (JSONObject) data;
                food.setText(d.optString("food"));
                cal.setText(num(d.optDouble("calories", 0)));
                pro.setText(num(d.optDouble("protein_g", 0)));
                carb.setText(num(d.optDouble("carbs_g", 0)));
                fat.setText(num(d.optDouble("fat_g", 0)));
                StringBuilder sb = new StringBuilder();
                JSONArray arr = d.optJSONArray("items");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject it = arr.optJSONObject(i);
                        if (it == null) continue;
                        sb.append("• ").append(it.optString("name"));
                        String q = it.optString("quantity");
                        if (!q.isEmpty()) sb.append(" (").append(q).append(")");
                        sb.append(" – ").append(num(it.optDouble("calories", 0))).append(" kcal\n");
                    }
                }
                items.setText(sb.toString().trim());
                result.setVisibility(View.VISIBLE);
                btnAnalyze.setEnabled(true);
                setBusy(false, "");
                scroll.post(() -> scroll.smoothScrollTo(0, result.getTop()));
            }, () -> btnAnalyze.setEnabled(true));
        } catch (Exception e) {
            setBusy(false, ""); setMsg(e.getMessage(), true); btnAnalyze.setEnabled(true);
        }
    }

    private void save() {
        btnSave.setEnabled(false);
        setBusy(true, "Saving…");
        try {
            JSONObject entry = new JSONObject()
                    .put("food", food.getText().toString().trim())
                    .put("calories", parse(cal))
                    .put("protein_g", parse(pro))
                    .put("carbs_g", parse(carb))
                    .put("fat_g", parse(fat));
            JSONObject req = new JSONObject().put("action", "save").put("entry", entry);
            call(req, data -> {
                showTotals((JSONObject) data);
                result.setVisibility(View.GONE);
                preview.setVisibility(View.GONE);
                note.setText("");
                imageBase64 = null;
                btnAnalyze.setEnabled(false);
                btnSave.setEnabled(true);
                setBusy(false, "");
                setMsg("Saved to your sheet ✅", false);
                scroll.post(() -> scroll.smoothScrollTo(0, 0));
            }, () -> btnSave.setEnabled(true));
        } catch (Exception e) {
            setBusy(false, ""); setMsg(e.getMessage(), true); btnSave.setEnabled(true);
        }
    }

    private void loadToday() {
        if (scriptUrl().isEmpty()) return;
        try {
            call(new JSONObject().put("action", "today"), data -> showTotals((JSONObject) data), null);
        } catch (Exception ignored) { }
    }

    private void showTotals(JSONObject t) {
        tCal.setText(num(t.optDouble("calories", 0)));
        tPro.setText(num(t.optDouble("protein_g", 0)));
        tCarb.setText(num(t.optDouble("carbs_g", 0)));
        tFat.setText(num(t.optDouble("fat_g", 0)));
        StringBuilder sb = new StringBuilder();
        JSONArray arr = t.optJSONArray("meals");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m == null) continue;
                sb.append(m.optString("time")).append(" · ").append(m.optString("food"))
                        .append("  –  ").append(m.optString("calories")).append(" kcal\n");
            }
        }
        meals.setText(sb.toString().trim());
    }

    // ---------- Networking ----------

    interface OnData { void run(Object data) throws Exception; }

    private void call(JSONObject req, OnData ok, Runnable onFail) throws Exception {
        String token = appToken();
        if (!token.isEmpty()) req.put("token", token);
        final String body = req.toString();
        final String url = scriptUrl();
        bg.execute(() -> {
            try {
                String resp = post(url, body);
                JSONObject json;
                try {
                    json = new JSONObject(resp);
                } catch (Exception e) {
                    throw new IOException("Unexpected reply from Apps Script. Check the URL and that access is set to \"Anyone\".");
                }
                if (!json.optBoolean("ok")) throw new IOException(json.optString("error", "Unknown error"));
                Object data = json.opt("data");
                runOnUiThread(() -> {
                    try { ok.run(data); } catch (Exception e) { setBusy(false, ""); setMsg(e.getMessage(), true); }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false, "");
                    setMsg(e.getMessage() == null ? e.toString() : e.getMessage(), true);
                    if (onFail != null) onFail.run();
                });
            }
        });
    }

    /** POST to Apps Script. It answers with a redirect that must be fetched with GET. */
    private static String post(String url, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(20000);
        c.setReadTimeout(90000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }

        int code = c.getResponseCode();
        int hops = 0;
        while ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308) && hops++ < 5) {
            String loc = c.getHeaderField("Location");
            c.disconnect();
            c = (HttpURLConnection) new URL(loc).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(90000);
            code = c.getResponseCode();
        }
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = in == null ? "" : read(in);
        c.disconnect();
        if (code >= 400) throw new IOException("Server error " + code);
        return text;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toString("UTF-8");
    }

    // ---------- Helpers ----------

    private void setBusy(boolean busy, String text) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        setMsg(text, false);
    }

    private void setMsg(String text, boolean error) {
        msg.setText(text);
        msg.setTextColor(error ? Color.parseColor("#DC2626") : Color.parseColor("#16A34A"));
    }

    private static String num(double v) { return String.valueOf(Math.round(v)); }

    private static double parse(EditText e) {
        try { return Double.parseDouble(e.getText().toString().trim()); } catch (Exception x) { return 0; }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bg.shutdown();
    }
}
