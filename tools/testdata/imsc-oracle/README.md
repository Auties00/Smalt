# IMSC timeline oracle

`timeline.js` writes `core/src/test/resources/subtitle/imsc-timeline.tsv`, the text that imscJS, the IMSC reference
implementation (sandflow/imscJS, BSD-2-Clause), shows in each document of the W3C IMSC test suite between
consecutive media time events. Core's `ImscConformanceTest` compares Smalt's TTML parser with it. The suite is the
`imsc-tests-b7f88fe.zip` row of `tools/testdata/vectors.tsv` (W3C Test Suite License or W3C 3-clause BSD License).

The tool runs on Node.js with two pinned packages, each downloaded into a directory of its own and checked:

| Package | Tarball | SHA-256 |
|---|---|---|
| imsc 1.1.5 | https://registry.npmjs.org/imsc/-/imsc-1.1.5.tgz | `44c53c20f9cc85285df6c42bd2869b9c9bf2f8a8926b51eaaf1471ddce9e460d` |
| sax 1.2.1 (ISC) | https://registry.npmjs.org/sax/-/sax-1.2.1.tgz | `5ffec88b13c25bd6fc01e939ef41799a86af66717cc5626c07d2c6eb0012a236` |

```
mkdir -p imscjs/node_modules/imsc imscjs/node_modules/sax
tar -xzf imsc-1.1.5.tgz -C imscjs/node_modules/imsc --strip-components=1
tar -xzf sax-1.2.1.tgz -C imscjs/node_modules/sax --strip-components=1
unzip -q imsc-tests-b7f88fe.zip -d suite
NODE_PATH=imscjs/node_modules node tools/testdata/imsc-oracle/timeline.js suite/imsc-tests-b7f88fee6a181126931a254408440f38d8985224 \
    | (head -1 > /dev/null; cat) > timeline.body
```

The committed file is the four header lines it starts with followed by `timeline.body`. The tool loads imscJS's
document and ISD modules directly, as its main module also loads the HTML renderer, which needs a browser.
