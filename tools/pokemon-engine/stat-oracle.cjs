'use strict';
// Test-only independent numerical oracle; production never starts this process.
const fs = require('node:fs');
const {Stats, Generations} = require('@smogon/calc');
if (require('@smogon/calc/package.json').version !== '0.12.0') throw Error('Unexpected oracle version');
const rows = JSON.parse(fs.readFileSync(0, 'utf8'));
if (!Array.isArray(rows) || rows.length > 2000) throw Error('Invalid oracle batch');
const keys = ['hp','atk','def','spa','spd','spe'];
const gen = Generations.get(9);
process.stdout.write(JSON.stringify(rows.map(row => Object.fromEntries(keys.map(key => [key,
  Stats.calcStat(gen, key, row.base[key], row.build.ivs[key], row.build.evs[key], row.level, row.build.nature)
])))));
