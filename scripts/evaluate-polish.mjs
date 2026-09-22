#!/usr/bin/env node
// Local smoke evaluation. Anchor checks are diagnostics, not a quality score.
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';

const baseUrl = process.env.MOXU_POLISH_BASE_URL || 'http://127.0.0.1:11434';
const model = process.env.MOXU_POLISH_MODEL || 'moxu-polish:qwen3-1.7b';
const destination = resolve(process.argv[2] || 'work/polish-ollama-results.json');
const cases = [
  {
    name: '悬疑与人物秘密',
    source: '林舟缓缓推开仓库的铁门，一股潮湿的气息扑面而来。他的目光落在角落的木箱上，眼底闪过一抹复杂的神色。\n“账本就在里面。”沈岚压低声音说道，“但钥匙只有陈叔有。”\n林舟没有回答，只是从口袋里取出那把生锈的钥匙。三天前，陈叔把钥匙交给他时，只说了一句话：别让沈岚知道。\n这一刻，他终于意识到，事情远比想象中复杂。',
    anchors: ['林舟', '沈岚', '陈叔', '铁门', '木箱', '三天前', '别让沈岚知道'],
  },
  {
    name: '数字、否定和对白',
    source: '“不是三万，是三千。”周敏把收据推到桌子中央，“9月17日晚上八点，钱就到账了。”\n赵川摇了摇头：“我没收过这笔钱，也没去过南站。”\n窗外的雨愈发猛烈，仿佛在为这场无声的博弈奏响序曲。周敏没有继续追问，只将那张写着302的房卡放在收据旁边。赵川伸出的手停在半空。',
    anchors: ['三万', '三千', '9月17日', '八点', '302', '房卡', '南站', '没收过', '没去过'],
  },
  {
    name: '克制原文',
    source: '锅里的水开了。\n“面还下不下？”\n陈宁看了眼手机，没有新消息。\n“下吧。”他说。\n母亲拿了两个碗，他把其中一个放回柜子。\n“她不来了？”\n陈宁拧小火，没答。',
    anchors: ['陈宁', '手机', '母亲', '碗', '柜子'],
  },
  {
    name: '动作与因果',
    source: '顾远并没有拔剑。他先把受伤的孩子推到石柱后面，随后才抬起左手挡住迎面劈来的木棍。手臂传来一阵剧痛，他却连眉头都没有皱一下。\n“跑！”他朝孩子喊道。\n孩子没有动。他的右脚被倒塌的木架压住了。顾远这才看见地上的血。他松开木棍，蹲下去搬木架，背上随即挨了重重的一下。\n他知道，自己已经没有退路。',
    anchors: ['顾远', '孩子', '右脚', '木架', '血'],
  },
];

const system = '你是一位写了十几年网文的老作者。用户给你一段AI写的小说，你把它改成真人手写的味道：剧情、对白信息、人物动作一律不改不加，只换表达。直接输出改好的正文，不要解释。';
const rows = [];
for (const sample of cases) {
  const started = performance.now();
  const response = await fetch(`${baseUrl.replace(/\/$/, '')}/api/chat`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    signal: AbortSignal.timeout(120000),
    body: JSON.stringify({
      model, stream: false, think: false,
      messages: [{ role: 'system', content: system }, { role: 'user', content: `把这段去AI味，只改这一段，不许往后续写：\n\n${sample.source}` }],
      options: { temperature: 0.2, top_p: 0.9, repeat_penalty: 1.08, seed: 42, num_ctx: 8192, num_predict: 1024 },
    }),
  });
  if (!response.ok) throw new Error(`Ollama returned HTTP ${response.status}`);
  const data = await response.json();
  const output = String(data.message?.content || '').replace(/<think>[\s\S]*?<\/think>/g, '').trim();
  const row = {
    case: sample.name, model, source: sample.source, output,
    seconds: Number(((performance.now() - started) / 1000).toFixed(2)),
    loadSeconds: Number(((data.load_duration || 0) / 1e9).toFixed(2)),
    generatedTokens: data.eval_count,
    tokensPerSecond: data.eval_duration ? Number((data.eval_count / (data.eval_duration / 1e9)).toFixed(1)) : null,
    doneReason: data.done_reason,
    lengthRatio: Number((output.length / sample.source.length).toFixed(2)),
    changed: output !== sample.source,
    missingAnchors: sample.anchors.filter(anchor => !output.includes(anchor)),
  };
  rows.push(row);
  console.log(JSON.stringify({ ...row, source: undefined, output: undefined }));
  await mkdir(dirname(destination), { recursive: true });
  await writeFile(destination, JSON.stringify({ runtime: 'Ollama', model, date: new Date().toISOString(), rows }, null, 2));
}
console.log(`Saved examples for manual review: ${destination}`);
