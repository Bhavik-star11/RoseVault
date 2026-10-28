import {generateKeyPairSync} from 'node:crypto';
import {readFileSync} from 'node:fs';
import {resolve,join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
const root=resolve(fileURLToPath(new URL('..',import.meta.url)));
const key=()=>generateKeyPairSync('ec',{namedCurve:'prime256v1'}).publicKey.export({type:'spki',format:'der'}).toString('base64');
const source=readFileSync(join(root,'tests/rls.sql.in'),'utf8').replaceAll('__ALICE_KEY__',key()).replaceAll('__BOB_KEY__',key());
const config=readFileSync(join(root,'supabase/config.toml'),'utf8');
const projectId=config.match(/^project_id\s*=\s*"([A-Za-z0-9_-]+)"/m)?.[1];
if(!projectId)throw new Error('Local Supabase project_id is missing');
// Execute the complete transaction with psql's simple-query protocol, not a prepared statement.
// Always targets the local Supabase Docker container; no linked/production credentials.
const result=spawnSync('docker',['exec','-i','-e','PGPASSWORD=postgres',`supabase_db_${projectId}`,'psql','-h','127.0.0.1','-U','postgres','-d','postgres','-v','ON_ERROR_STOP=1'],{cwd:root,input:source,stdio:['pipe','inherit','inherit']});
process.exitCode=result.status??1;
