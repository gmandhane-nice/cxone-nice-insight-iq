# Video Recording Guide

## Option 1: Screen Recording (Simplest)

### macOS Built-in (QuickTime)
1. Open QuickTime Player
2. File → New Screen Recording
3. Click the dropdown arrow → select "Internal Microphone" for voiceover
4. Click Record, select area or full screen
5. Follow the DEMO_SCRIPT.md step by step
6. Press Cmd+Ctrl+Esc to stop
7. Save as .mov

### OBS Studio (Free, Professional)
1. Download from https://obsproject.com
2. Add Sources: "Display Capture" + "Audio Input Capture" (mic)
3. Settings → Output → Recording Format: MP4, Quality: High
4. Settings → Video → Resolution: 1920x1080
5. Click "Start Recording"
6. Follow demo script
7. Click "Stop Recording"

### Loom (Easiest for sharing)
1. Install Loom Chrome extension or desktop app
2. Click Loom icon → Screen + Cam (optional face cam)
3. Record following the script
4. Auto-uploads with shareable link

## Option 2: Automated Demo with Puppeteer

Create a script that auto-navigates the app and captures screenshots/video:

```bash
# Install dependencies
npm init -y
npm install puppeteer puppeteer-screen-recorder
```

```javascript
// demo-recorder.js
const puppeteer = require('puppeteer');
const { PuppeteerScreenRecorder } = require('puppeteer-screen-recorder');

const DELAY = 2000; // ms between actions

async function recordDemo() {
  const browser = await puppeteer.launch({
    headless: false,
    args: ['--window-size=1920,1080']
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1920, height: 1080 });

  const recorder = new PuppeteerScreenRecorder(page, {
    fps: 30,
    videoFrame: { width: 1920, height: 1080 }
  });

  await recorder.start('./demo-video.mp4');
  await page.goto('http://localhost:8080', { waitUntil: 'networkidle0' });
  await wait(DELAY);

  // Tab 1: ROI (default landing)
  await clickTab(page, 'ROI');
  await wait(3000); // Let data load

  // Tab 2: Briefing
  await clickTab(page, 'Briefing');
  await page.click('button.refresh-btn'); // Generate Briefing
  await wait(12000); // Wait for LLM response

  // Tab 3: Forecast
  await clickTab(page, 'Forecast');
  await wait(3000);

  // Tab 4: Risk Monitor
  await clickTab(page, 'Risk Monitor');
  await wait(3000);

  // Tab 5: Coaching
  await clickTab(page, 'Coaching');
  await wait(3000);

  // Tab 6: Burnout
  await clickTab(page, 'Burnout');
  await wait(3000);

  // Tab 7: Anomaly
  await clickTab(page, 'Anomaly');
  await page.click('button.refresh-btn');
  await wait(3000);

  // Tab 8: Simulator
  await clickTab(page, 'Simulator');
  await wait(2000);

  // Tab 9: Shrinkage
  await clickTab(page, 'Shrinkage');
  await wait(3000);

  // Tab 10: Deflection
  await clickTab(page, 'Deflection');
  await wait(3000);

  // Tab 11: RCA Chat
  await clickTab(page, 'RCA Chat');
  await wait(1000);
  await page.type('#chatInput', 'Which skills have the highest refusal rate?', { delay: 50 });
  await page.click('#askBtn');
  await wait(15000); // Wait for AI response

  await recorder.stop();
  await browser.close();
  console.log('Demo video saved to ./demo-video.mp4');
}

async function clickTab(page, tabName) {
  const buttons = await page.$$('nav button');
  for (const btn of buttons) {
    const text = await page.evaluate(el => el.textContent, btn);
    if (text.trim() === tabName) {
      await btn.click();
      break;
    }
  }
  await wait(500);
}

function wait(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

recordDemo().catch(console.error);
```

Run: `node demo-recorder.js`

## Option 3: GIF Screenshots for Slides

```bash
# Install gifski for high-quality GIFs
brew install gifski

# Take screenshots with Chrome DevTools
# Cmd+Shift+P → "Capture full size screenshot" in each tab
# Then combine into a GIF:
gifski -o demo.gif --fps 2 --width 1200 screenshot*.png
```

## Recommended Approach

For a sparkathon submission:
1. **Live demo** if presenting in person (use DEMO_SCRIPT.md)
2. **Loom recording** if submitting async (5-8 min, face cam optional)
3. **OBS + voiceover** if you want polished production quality

## Post-Processing Tips

- Trim dead time (loading screens) in iMovie or QuickTime (Edit → Trim)
- Add speed-up (2x) during loading/waiting periods
- Add captions for key numbers ($62M, 41K contacts, etc.)
- Keep total under 10 minutes for attention spans
