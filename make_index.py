#!/usr/bin/env python3
"""Builds app/src/main/assets/index.html from web/app.html.

Run this whenever web/app.html is replaced with a newer version of the web app:
    python3 tools/make_index.py
"""
import pathlib

root = pathlib.Path(__file__).resolve().parent.parent
app = (root / "web" / "app.html").read_text(encoding="utf-8")

SHIM = r"""<script>
/* Android shell: connects the page to native code (file saving, notifications, speech, alarms). */
(function(){
  var A=window.AndroidBridge; if(!A) return;

  /* ---- saving files (sent in 1.5 MB pieces so big backups work) ---- */
  function b64(blob){return new Promise(function(res,rej){var r=new FileReader();r.onload=function(){var s=String(r.result);res(s.slice(s.indexOf(',')+1))};r.onerror=function(){rej(r.error)};r.readAsDataURL(blob)})}
  var downloads={save:async function(o){
    var d=o.data,blob=(d instanceof Blob)?d:new Blob([d],{type:o.type||'text/plain'}),fail=function(){var e=new Error('Could not save the file');e.code='failed';throw e};
    if(!A.saveBegin(String(o.filename||'file'),blob.type||'application/octet-stream'))fail();
    var STEP=3*512*1024;
    for(var i=0;i<blob.size;i+=STEP){if(!A.saveChunk(await b64(blob.slice(i,i+STEP))))fail()}
    if(!A.saveEnd())fail();
    return true}};
  window.claude={use:async function(n){return n==='downloads'?downloads:null}};

  /* ---- notifications ---- */
  function N(t,o){A.notify(String(t),(o&&o.body)||'');this.close=function(){}}
  N.permission='granted';N.requestPermission=function(){return Promise.resolve('granted')};
  try{window.Notification=N}catch(e){}

  /* ---- speech recognition backed by Android's SpeechRecognizer ---- */
  if(A.speechStart){
    var cur=null;
    function SR(){this.lang='en-US';this.continuous=false;this.interimResults=false;this.maxAlternatives=1;this.processLocally=false}
    SR.prototype.start=function(){
      if(cur&&cur!==this){try{A.speechAbort()}catch(e){}}
      cur=this;this._fin=[];
      A.speechStart(String(this.lang||'en-US'),!!this.continuous,!!this.interimResults,!!this.processLocally)};
    SR.prototype.stop=function(){A.speechStop()};
    SR.prototype.abort=function(){A.speechAbort()};
    function item(t,fin){var o=[{transcript:t,confidence:0.9}];o.isFinal=fin;o.item=function(i){return this[i]};return o}
    window.__srEvt=function(type,text){
      var r=cur;if(!r)return;
      function emit(n,e){var f=r['on'+n];if(typeof f==='function'){try{f.call(r,e||{})}catch(x){}}}
      if(type==='start'){emit('start');emit('audiostart');emit('soundstart');emit('speechstart');return}
      if(type==='partial'||type==='final'){
        var list;
        if(type==='final'){r._fin.push(item(text,true));list=r._fin.slice();emit('result',{resultIndex:list.length-1,results:wrap(list)})}
        else{list=r._fin.concat([item(text,false)]);emit('result',{resultIndex:r._fin.length,results:wrap(list)})}
        return}
      if(type==='error'){emit('error',{error:text,message:text});return}
      if(type==='end'){emit('speechend');emit('audioend');emit('end');return}
    };
    function wrap(a){a.item=function(i){return this[i]};return a}
    window.SpeechRecognition=window.webkitSpeechRecognition=SR;
  }
  window.__ANDROID_APP=true;
})();
</script>"""

html = (
    '<!doctype html><html lang="en"><head><meta charset="utf-8">'
    '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">'
    '<style>:root{color-scheme:light dark}html,body{margin:0}body{font:14px system-ui,sans-serif}'
    '[hidden]{display:none!important}img{max-width:100%}#ibtn{display:none!important}</style>'
    + SHIM + "</head><body>" + app + "</body></html>"
)
out = root / "app" / "src" / "main" / "assets" / "index.html"
out.write_text(html, encoding="utf-8")
print("wrote", out, len(html), "bytes")
