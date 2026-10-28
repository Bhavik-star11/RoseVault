import {generateKeyPairSync} from 'node:crypto';
import {readFileSync,writeFileSync,mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {spawnSync} from 'node:child_process';
const root=resolve(new URL('..',import.meta.url).pathname);
const key=()=>generateKeyPairSync('ec',{namedCurve:'prime256v1'}).publicKey.export({type:'spki',format:'der'}).toString('base64');
const source=readFileSync(join(root,'tests/rls.sql.in'),'utf8').replaceAll('__ALICE_KEY__',key()).replaceAll('__BOB_KEY__',key());
const dir=mkdtempSync(join(tmpdir(),'rosevault-rls-'));
try {
 const file=join(dir,'contract.sql');writeFileSync(file,source);
 // Intentionally local-only. No linked project, database secret, or external auth service.
 const result=spawnSync(join(root,'node_modules/.bin/supabase'),['db','query','--local','--file',file],{cwd:root,stdio:'inherit'});
 process.exitCode=result.status??1;
}finally{rmSync(dir,{recursive:true,force:true});}
