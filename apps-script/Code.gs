/**
 * Food Calorie Tracker - Google Apps Script backend
 * Paste this into: your "food calorie" sheet > Extensions > Apps Script > Code.gs
 */

const SHEET_NAME = '';                 // leave blank to use the first tab, or put your tab name
const GEMINI_MODEL = 'gemini-3.8-flash'; // change here if Google renames the model

function doGet() {
  return HtmlService.createHtmlOutputFromFile('Index')
    .setTitle('Food Calorie Tracker')
    .addMetaTag('viewport', 'width=device-width, initial-scale=1, maximum-scale=1');
}

/** Reads a script property, retrying because Google storage sometimes fails briefly. */
function getProp_(name) {
  const cache = CacheService.getScriptCache();
  const cached = cache.get('prop_' + name);
  if (cached) return cached;
  let lastErr;
  for (let i = 0; i < 4; i++) {
    try {
      const v = PropertiesService.getScriptProperties().getProperty(name);
      if (v) cache.put('prop_' + name, v, 21600);
      return v;
    } catch (e) {
      lastErr = e;
      Utilities.sleep(500 * (i + 1));
    }
  }
  throw lastErr;
}

function getSheet_() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const sheet = SHEET_NAME ? ss.getSheetByName(SHEET_NAME) : ss.getSheets()[0];
  if (sheet.getLastRow() === 0) {
    sheet.appendRow(['DateTime', 'Food', 'Calories_kcal', 'Protein_g', 'Carbs_g', 'Fat_g']);
  }
  return sheet;
}

/** Sends the photo to Gemini and returns estimated nutrition. Nothing is saved yet. */
function analyzeFood(base64, mimeType, note) {
  const key = getProp_('GEMINI_API_KEY');
  if (!key) throw new Error('API key missing. Add GEMINI_API_KEY in Project Settings > Script properties.');

  const prompt =
    'You are a nutrition expert familiar with Indian and international food. ' +
    'Identify every food item in this photo, estimate the portion size you can see, ' +
    'and estimate total nutrition for the whole plate. ' +
    (note ? 'Extra info from the user (use it for item names / quantity): "' + note + '". ' : '') +
    'Reply ONLY with JSON in this exact shape: ' +
    '{"food":"short name e.g. Idli (3) with sambar","calories":0,"protein_g":0,"carbs_g":0,"fat_g":0,' +
    '"items":[{"name":"","quantity":"","calories":0}]}. ' +
    'If there is no food in the photo, return {"food":"NO_FOOD"}.';

  const payload = {
    contents: [{ parts: [
      { text: prompt },
      { inline_data: { mime_type: mimeType, data: base64 } }
    ]}],
    generationConfig: { responseMimeType: 'application/json' }
  };

  const url = 'https://generativelanguage.googleapis.com/v1beta/models/' + GEMINI_MODEL + ':generateContent';
  const res = UrlFetchApp.fetch(url, {
    method: 'post',
    contentType: 'application/json',
    headers: { 'x-goog-api-key': key },
    payload: JSON.stringify(payload),
    muteHttpExceptions: true
  });

  if (res.getResponseCode() !== 200) {
    throw new Error('Gemini error ' + res.getResponseCode() + ': ' + res.getContentText().slice(0, 300));
  }

  const body = JSON.parse(res.getContentText());
  const parts = (body.candidates && body.candidates[0].content && body.candidates[0].content.parts) || [];
  const text = parts.filter(p => p.text && !p.thought).map(p => p.text).join('');
  if (!text) throw new Error('Gemini returned no answer. Try again.');
  const data = JSON.parse(text.replace(/```json|```/g, '').trim());
  if (data.food === 'NO_FOOD') throw new Error('No food found in the photo. Try another picture.');
  return data;
}

/** Saves one row (after you confirm / edit the values) and returns today's totals. */
function saveEntry(d) {
  const sheet = getSheet_();
  sheet.appendRow([
    new Date(),
    d.food,
    Math.round(Number(d.calories) || 0),
    Math.round(Number(d.protein_g) || 0),
    Math.round(Number(d.carbs_g) || 0),
    Math.round(Number(d.fat_g) || 0)
  ]);
  sheet.getRange(sheet.getLastRow(), 1).setNumberFormat('dd/MM/yyyy HH:mm');
  return getTodayTotals();
}

/** Adds up every row with today's date. */
function getTodayTotals() {
  const sheet = getSheet_();
  const rows = sheet.getDataRange().getValues().slice(1);
  const tz = Session.getScriptTimeZone();
  const today = Utilities.formatDate(new Date(), tz, 'yyyy-MM-dd');
  const t = { calories: 0, protein_g: 0, carbs_g: 0, fat_g: 0, meals: [] };

  rows.forEach(r => {
    if (!(r[0] instanceof Date)) return;
    if (Utilities.formatDate(r[0], tz, 'yyyy-MM-dd') !== today) return;
    t.calories += Number(r[2]) || 0;
    t.protein_g += Number(r[3]) || 0;
    t.carbs_g += Number(r[4]) || 0;
    t.fat_g += Number(r[5]) || 0;
    t.meals.push({ time: Utilities.formatDate(r[0], tz, 'hh:mm a'), food: r[1], calories: r[2] });
  });
  return t;
}

/* ---------- Used by the Android app (APK) ---------- */
function doPost(e) {
  try {
    const req = JSON.parse(e.postData.contents);
    const token = getProp_('APP_TOKEN');
    if (token && req.token !== token) throw new Error('Wrong app password. Check Settings in the app.');
    let out;
    if (req.action === 'analyze') out = analyzeFood(req.image, req.mimeType || 'image/jpeg', req.note || '');
    else if (req.action === 'save') out = saveEntry(req.entry);
    else if (req.action === 'today') out = getTodayTotals();
    else throw new Error('Unknown action');
    return json_({ ok: true, data: out });
  } catch (err) {
    return json_({ ok: false, error: String(err && err.message || err) });
  }
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
