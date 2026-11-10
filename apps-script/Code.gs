/**
 * Food Calorie Tracker - Google Apps Script backend
 * Paste this into: your "food calorie" sheet > Extensions > Apps Script > Code.gs
 */

const SHEET_NAME = '';                 // leave blank to use the first tab, or put your tab name
// Tried in order. If one is busy (503) or retired (404), the next one is used.
const GEMINI_MODELS = ['gemini-3.8-flash', 'gemini-3.5-flash-lite'];   // 3.5 Flash-Lite has a much bigger free daily quota
// Optional free backup: set OPENROUTER_API_KEY (openrouter.ai) to use its free models when Gemini is busy.
const OPENROUTER_MODEL = 'openrouter/free';
// Used when the script property CLAUDE_API_KEY is set (Claude first, Gemini as backup).
const CLAUDE_MODEL = 'claude-sonnet-5-5';   // or 'claude-haiku-5-5' for cheaper / faster

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

/** Calls Gemini with retries on busy errors and fallback to the next model. */
function callGemini_(key, payload) {
  let lastMsg = '';
  for (const model of GEMINI_MODELS) {
    const url = 'https://generativelanguage.googleapis.com/v1beta/models/' + model + ':generateContent';
    for (let attempt = 0; attempt < 3; attempt++) {
      const res = UrlFetchApp.fetch(url, {
        method: 'post',
        contentType: 'application/json',
        headers: { 'x-goog-api-key': key },
        payload: JSON.stringify(payload),
        muteHttpExceptions: true
      });
      const code = res.getResponseCode();
      if (code === 200) return JSON.parse(res.getContentText());
      lastMsg = 'Gemini error ' + code + ' (' + model + '): ' + res.getContentText().slice(0, 300);
      if (code === 404 || code === 429) break;                   // retired or daily free quota used -> next model
      if (code >= 500) { Utilities.sleep(1500 * (attempt + 1)); continue; } // busy -> retry
      throw new Error(lastMsg);                                  // bad key etc. -> stop
    }
  }
  throw new Error('Gemini is busy right now, please try again in a minute. ' + lastMsg);
}

function getSheet_() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const sheet = SHEET_NAME ? ss.getSheetByName(SHEET_NAME) : ss.getSheets()[0];
  if (sheet.getLastRow() === 0) {
    sheet.appendRow(['DateTime', 'Food', 'Calories_kcal', 'Protein_g', 'Carbs_g', 'Fat_g']);
  }
  return sheet;
}

/** Sends the photo to Claude or Gemini and returns estimated nutrition. Nothing is saved yet. */
function analyzeFood(base64, mimeType, note) {
  const prompt =
    'You are a nutrition expert familiar with Indian and international food. ' +
    'Identify every food item in this photo, estimate the portion size you can see, ' +
    'and estimate total nutrition for the whole plate. ' +
    (note ? 'Extra info from the user (use it for item names / quantity): "' + note + '". ' : '') +
    'Reply ONLY with JSON (no markdown, no explanation) in this exact shape: ' +
    '{"food":"short name e.g. Idli (3) with sambar","calories":0,"protein_g":0,"carbs_g":0,"fat_g":0,' +
    '"items":[{"name":"","quantity":"","calories":0}]}. ' +
    'If there is no food in the photo, return {"food":"NO_FOOD"}.';

  const providers = [
    ['Claude', getProp_('CLAUDE_API_KEY'), askClaude_],
    ['Gemini', getProp_('GEMINI_API_KEY'), askGemini_],
    ['OpenRouter', getProp_('OPENROUTER_API_KEY'), askOpenRouter_]
  ].filter(p => p[1]);
  if (!providers.length) {
    throw new Error('No API key. Add GEMINI_API_KEY (free) in Project Settings > Script properties.');
  }

  let text;
  const errors = [];
  for (const [name, key, fn] of providers) {
    try { text = fn(key, prompt, base64, mimeType); break; }
    catch (e) { errors.push(name + ': ' + e.message); }
  }
  if (!text) throw new Error(errors.join(' | '));

  const json = text.replace(/```json|```/g, '').trim();
  const data = JSON.parse(json.slice(json.indexOf('{'), json.lastIndexOf('}') + 1));
  if (data.food === 'NO_FOOD') throw new Error('No food found in the photo. Try another picture.');
  return data;
}

/** Claude (Anthropic Messages API). */
function askClaude_(key, prompt, base64, mimeType) {
  const payload = {
    model: CLAUDE_MODEL,
    max_tokens: 1024,
    messages: [{
      role: 'user',
      content: [
        { type: 'image', source: { type: 'base64', media_type: mimeType, data: base64 } },
        { type: 'text', text: prompt }
      ]
    }]
  };
  let lastMsg = '';
  for (let attempt = 0; attempt < 3; attempt++) {
    const res = UrlFetchApp.fetch('https://api.anthropic.com/v1/messages', {
      method: 'post',
      contentType: 'application/json',
      headers: { 'x-api-key': key, 'anthropic-version': '2023-06-01' },
      payload: JSON.stringify(payload),
      muteHttpExceptions: true
    });
    const code = res.getResponseCode();
    if (code === 200) {
      const body = JSON.parse(res.getContentText());
      const text = (body.content || []).filter(c => c.type === 'text').map(c => c.text).join('');
      if (!text) throw new Error('Claude returned no answer. Try again.');
      return text;
    }
    lastMsg = 'Claude error ' + code + ': ' + res.getContentText().slice(0, 300);
    if (code === 429 || code === 529 || code >= 500) { Utilities.sleep(1500 * (attempt + 1)); continue; }
    throw new Error(lastMsg);   // bad key, no credits, etc.
  }
  throw new Error(lastMsg);
}

/** OpenRouter free models (OpenAI-compatible). */
function askOpenRouter_(key, prompt, base64, mimeType) {
  const payload = {
    model: OPENROUTER_MODEL,
    messages: [{
      role: 'user',
      content: [
        { type: 'text', text: prompt },
        { type: 'image_url', image_url: { url: 'data:' + mimeType + ';base64,' + base64 } }
      ]
    }]
  };
  const res = UrlFetchApp.fetch('https://openrouter.ai/api/v1/chat/completions', {
    method: 'post',
    contentType: 'application/json',
    headers: { 'Authorization': 'Bearer ' + key },
    payload: JSON.stringify(payload),
    muteHttpExceptions: true
  });
  const code = res.getResponseCode();
  if (code !== 200) throw new Error('OpenRouter error ' + code + ': ' + res.getContentText().slice(0, 300));
  const body = JSON.parse(res.getContentText());
  const text = body.choices && body.choices[0] && body.choices[0].message && body.choices[0].message.content;
  if (!text) throw new Error('OpenRouter returned no answer.');
  return text;
}

/** Gemini (Google). */
function askGemini_(key, prompt, base64, mimeType) {
  const payload = {
    contents: [{ parts: [
      { text: prompt },
      { inline_data: { mime_type: mimeType, data: base64 } }
    ]}],
    generationConfig: { responseMimeType: 'application/json' }
  };
  const body = callGemini_(key, payload);
  const parts = (body.candidates && body.candidates[0].content && body.candidates[0].content.parts) || [];
  const text = parts.filter(p => p.text && !p.thought).map(p => p.text).join('');
  if (!text) throw new Error('Gemini returned no answer. Try again.');
  return text;
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
