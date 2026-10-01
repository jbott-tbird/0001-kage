// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

// Export the evaluated web adapter, rather than maintaining a second set of mock mail.
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const ts = require('typescript');
const source = path.resolve(__dirname, '../src/lib/mail.ts');
const compiled = ts.transpileModule(fs.readFileSync(source, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true },
}).outputText;
const adapter = new Module(source, module);
adapter.filename = source;
adapter.paths = Module._nodeModulePaths(path.dirname(source));
adapter._compile(compiled, source);
const { initialState, populateDemoMailbox, SNAPSHOT } = adapter.exports;
const template = { id: 'template', name: 'Demo', address: 'demo@example.net', protocol: 'imap', color: 'purple' };
const state = initialState();
const addedAccount = populateDemoMailbox({ ...state, accounts: [template], folders: [], messages: [] }, template);
const output = path.resolve(__dirname, '../../Android/app/src/main/assets/demo-mail.json');
fs.writeFileSync(output, JSON.stringify({ ...state, clock: SNAPSHOT, addedAccount }, null, 2) + '\n');
console.log(`Exported ${state.accounts.length} accounts, ${state.messages.length} messages, and new-account fixtures`);
