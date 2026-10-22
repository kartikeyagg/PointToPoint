package com.kartikey.p2pchat.remote;

/** The single page {@link WebHost} serves: login, live screen, touch / trackpad and keyboard input. */
final class WebPage {
    private WebPage() {}

    static final String HTML = """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">
            <meta name="apple-mobile-web-app-capable" content="yes">
            <title>Remote screen</title>
            <style>
              html,body{margin:0;height:100%;background:#111;color:#eee;font:16px -apple-system,system-ui,sans-serif;overflow:hidden}
              #login{position:fixed;inset:0;display:flex;flex-direction:column;gap:12px;align-items:center;justify-content:center}
              #login input,#login button{font-size:18px;padding:10px 14px;border-radius:8px;border:0}
              #login button{background:#2d7ff9;color:#fff}
              #err{color:#f66;min-height:1.2em}
              #wrap{position:fixed;inset:0;display:none;align-items:center;justify-content:center}
              #screen{max-width:100%;max-height:100%;touch-action:none;user-select:none;-webkit-user-select:none}
              #bar{position:fixed;top:env(safe-area-inset-top);right:8px;display:none;gap:8px;opacity:.85}
              #bar button{font-size:15px;padding:8px 12px;border-radius:8px;border:0;background:#333;color:#eee}
              #kbd{position:fixed;left:-100px;top:0;width:10px;opacity:0}
              #badge{position:fixed;left:8px;top:env(safe-area-inset-top);font-size:13px;opacity:.7;display:none}
            </style></head><body>
            <div id="login">
              <div>Passphrase of the computer you want to see</div>
              <input id="pw" type="password" autocomplete="current-password" placeholder="passphrase">
              <button id="go">Connect</button><div id="err"></div>
            </div>
            <div id="wrap"><img id="screen" alt=""></div>
            <div id="badge"></div>
            <div id="bar"><button id="kb">Keyboard</button><button id="rc">Right-click</button><button id="out">Disconnect</button></div>
            <input id="kbd" autocapitalize="off" autocorrect="off" autocomplete="off" spellcheck="false">
            <script>
            const $=id=>document.getElementById(id);
            let key=null, busy=false, queue=[], rightNext=false, canControl=true, alive=false;

            async function post(){
              if(!queue.length||busy)return; busy=true;
              const body=queue.join("\\n"); queue=[];
              try{await fetch("/input",{method:"POST",headers:{"X-Key":key},body});}catch(e){}
              busy=false; post();
            }
            function send(l){queue.push(l);post();}

            async function loop(){
              while(alive){
                try{
                  const r=await fetch("/frame?"+Date.now(),{headers:{"X-Key":key}});
                  if(r.status===401){logout("Wrong passphrase");return;}
                  canControl=r.headers.get("X-Control")==="1";
                  $("badge").textContent=canControl?"":"View only";
                  const b=await r.blob(), url=URL.createObjectURL(b), img=$("screen");
                  const old=img.src; img.src=url; if(old.startsWith("blob:"))URL.revokeObjectURL(old);
                }catch(e){await new Promise(s=>setTimeout(s,800));}
                await new Promise(s=>setTimeout(s,90));
              }
            }
            function logout(msg){alive=false;key=null;$("wrap").style.display=$("bar").style.display=$("badge").style.display="none";
              $("login").style.display="flex";$("err").textContent=msg||"";}
            async function connect(){
              key=$("pw").value;
              const r=await fetch("/frame",{headers:{"X-Key":key}}).catch(()=>null);
              if(!r){$("err").textContent="Cannot reach the computer";return;}
              if(r.status!==200){$("err").textContent="Wrong passphrase";return;}
              $("login").style.display="none";$("wrap").style.display="flex";$("bar").style.display="flex";$("badge").style.display="block";
              alive=true; loop();
            }
            $("go").onclick=connect; $("pw").onkeydown=e=>{if(e.key==="Enter")connect();};
            $("out").onclick=()=>logout("");
            $("kb").onclick=()=>{$("kbd").focus();};
            $("rc").onclick=()=>{rightNext=!rightNext;$("rc").style.background=rightNext?"#2d7ff9":"#333";};

            function frac(t){const r=$("screen").getBoundingClientRect();
              return [Math.min(1,Math.max(0,(t.clientX-r.left)/r.width)).toFixed(5),Math.min(1,Math.max(0,(t.clientY-r.top)/r.height)).toFixed(5)];}
            const scr=$("screen"); let g=null, lastTap=0;
            function dist(a,b){return Math.hypot(a.clientX-b.clientX,a.clientY-b.clientY);}
            scr.addEventListener("touchstart",e=>{e.preventDefault();
              if(e.touches.length===2){g={scroll:true,y:(e.touches[0].clientY+e.touches[1].clientY)/2,acc:0,pos:frac(e.touches[0])};return;}
              const t=e.touches[0], p=frac(t);
              g={x:t.clientX,y:t.clientY,moved:false,down:false,right:rightNext,t:Date.now(),p,
                 timer:setTimeout(()=>{if(g&&!g.moved&&!g.down){g.longDone=true;send("m "+g.p.join(" "));send("d "+g.p.join(" ")+" 2");send("u "+g.p.join(" ")+" 2");}},550)};
              send("m "+p.join(" "));},{passive:false});
            scr.addEventListener("touchmove",e=>{e.preventDefault(); if(!g)return;
              if(g.scroll&&e.touches.length===2){const y=(e.touches[0].clientY+e.touches[1].clientY)/2; g.acc+=(g.y-y); g.y=y;
                const n=Math.trunc(g.acc/25); if(n){g.acc-=n*25; send("w "+g.pos.join(" ")+" "+n);} return;}
              if(g.scroll)return;
              const t=e.touches[0]; const p=frac(t);
              if(!g.moved&&dist(t,{clientX:g.x,clientY:g.y})>8){g.moved=true;clearTimeout(g.timer);
                send("d "+g.p.join(" ")+" "+(g.right?2:0)); g.down=true;}
              if(g.moved)send("m "+p.join(" "));},{passive:false});
            scr.addEventListener("touchend",e=>{e.preventDefault(); if(!g)return; clearTimeout(g.timer);
              if(g.scroll){if(e.touches.length===0)g=null;return;}
              const t=e.changedTouches[0], p=frac(t);
              if(g.down){send("u "+p.join(" ")+" "+(g.right?2:0));}
              else if(!g.longDone){const b=g.right?2:0; send("d "+g.p.join(" ")+" "+b); send("u "+g.p.join(" ")+" "+b);
                if(g.right){rightNext=false;$("rc").style.background="#333";}}
              g=null;},{passive:false});
            // trackpad / mouse attached to the iPad
            scr.addEventListener("mousemove",e=>{if(e.buttons===0||true)send("m "+frac(e).join(" "));});
            scr.addEventListener("mousedown",e=>{send("d "+frac(e).join(" ")+" "+e.button);});
            scr.addEventListener("mouseup",e=>{send("u "+frac(e).join(" ")+" "+e.button);});
            scr.addEventListener("contextmenu",e=>e.preventDefault());
            scr.addEventListener("wheel",e=>{e.preventDefault();send("w "+frac(e).join(" ")+" "+Math.sign(e.deltaY));},{passive:false});

            // keyboard: special keys by name, text by character (works with the iPad soft keyboard)
            const special=["Enter","Backspace","Tab","Escape","ArrowLeft","ArrowRight","ArrowUp","ArrowDown","Delete","Home","End","PageUp","PageDown"];
            const kbd=$("kbd");
            kbd.addEventListener("keydown",e=>{
              if(special.includes(e.key)){e.preventDefault();send("k "+e.key+" 1");send("k "+e.key+" 0");}
              else if((e.ctrlKey||e.metaKey||e.altKey)&&e.key.length===1){e.preventDefault();
                const mods=[e.ctrlKey&&"Control",e.metaKey&&"Meta",e.altKey&&"Alt"].filter(Boolean);
                mods.forEach(m=>send("k "+m+" 1")); send("t "+e.key); mods.forEach(m=>send("k "+m+" 0"));}});
            kbd.addEventListener("input",e=>{const v=kbd.value; kbd.value=""; if(v)send("t "+v.replace(/\\n/g,""));});
            </script></body></html>
            """;
}
