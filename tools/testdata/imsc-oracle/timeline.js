// Writes the visible text of every IMSC test document over time, as imscJS (sandflow/imscJS, BSD-2-Clause)
// computes it, for core's ImscConformanceTest to compare Smalt's TTML parser against.
//
// Usage: node timeline.js <extracted imsc-tests directory> > ../../../core/src/test/resources/subtitle/imsc-timeline.tsv
// with imsc 1.1.5 and its dependency sax 1.2.1 on NODE_PATH (see README.md for the pinned tarballs).
//
// Output: a header comment, then one line per document and stretch of time between two consecutive media time
// events of imscJS: the document path relative to the suite root, the start and end in microseconds, and the
// text of each paragraph shown during that stretch, tab separated, in document order. Paragraph texts have their
// line breaks written as \n, tabs as spaces, and spans hidden by tts:visibility left out. A document imscJS
// rejects is written once with the word ERROR in place of the times.
'use strict';
const fs = require('fs');
const path = require('path');
// the main module also loads the HTML renderer, which needs a browser
const imsc = {fromXML: require('imsc/src/main/js/doc').fromXML, generateISD: require('imsc/src/main/js/isd').generateISD};

const STYLING = 'http://www.w3.org/ns/ttml#styling';

function walk(directory, files) {
    for (const entry of fs.readdirSync(directory, {withFileTypes: true})) {
        const full = path.join(directory, entry.name);
        if (entry.isDirectory()) {
            walk(full, files);
        } else if (entry.name.endsWith('.ttml')) {
            files.push(full);
        }
    }
    return files;
}

function hidden(element) {
    return element.styleAttrs && element.styleAttrs[STYLING + ' visibility'] === 'hidden';
}

function paragraphText(element, out) {
    if (element.kind === 'br') {
        out.push('\n');
        return;
    }
    if ('text' in element) {
        if (!hidden(element)) {
            out.push(element.text);
        }
        return;
    }
    for (const child of element.contents || []) {
        paragraphText(child, out);
    }
}

function paragraphs(element, out) {
    if (element.kind === 'p') {
        const parts = [];
        paragraphText(element, parts);
        out.push(parts.join('').replace(/\t/g, ' ').replace(/\r?\n/g, '\\n'));
        return;
    }
    for (const child of element.contents || []) {
        paragraphs(child, out);
    }
}

function micros(seconds) {
    return Math.round(seconds * 1e6);
}

const root = process.argv[2];
const lines = ['# imscJS 1.1.5 timelines of the w3c/imsc-tests documents; regenerate with tools/testdata/imsc-oracle'];
for (const file of walk(root, []).sort()) {
    const name = path.relative(root, file).split(path.sep).join('/');
    let doc;
    try {
        doc = imsc.fromXML(fs.readFileSync(file, 'utf8'), {
            info: () => false, warn: () => false, error: () => false, fatal: () => true
        });
    } catch (exception) {
        doc = null;
    }
    if (!doc) {
        lines.push(name + '\tERROR');
        continue;
    }
    const events = doc.getMediaTimeEvents();
    for (let index = 0; index + 1 < events.length; index++) {
        const start = events[index];
        const end = events[index + 1];
        if (!isFinite(start) || !isFinite(end) || end <= start) {
            continue;
        }
        const isd = imsc.generateISD(doc, start, {info: () => false, warn: () => false, error: () => false,
            fatal: () => true});
        const texts = [];
        for (const region of isd.contents) {
            paragraphs(region, texts);
        }
        lines.push([name, micros(start), micros(end)].concat(texts).join('\t'));
    }
}
process.stdout.write(lines.join('\n') + '\n');
