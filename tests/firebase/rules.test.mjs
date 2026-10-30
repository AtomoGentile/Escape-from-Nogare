import {before,after,beforeEach,test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {initializeTestEnvironment,assertSucceeds,assertFails} from '@firebase/rules-unit-testing';
import {ref,set,get,update,remove,serverTimestamp} from 'firebase/database';
let env;
before(async()=>{env=await initializeTestEnvironment({projectId:'demo-nogar',database:{rules:readFileSync('firebase/database.rules.json','utf8')}})});
beforeEach(async()=>env.clearDatabase());after(async()=>env?.cleanup());
const proof='a'.repeat(64),path='nogar/v1/rooms/123456';
const db=uid=>env.authenticatedContext(uid).database();
async function fixture(){
 const owner=db('owner'),guest=db('guest'),stranger=db('stranger');
 await assertSucceeds(set(ref(owner,path),{owner:'owner',proof,createdAt:serverTimestamp(),pulse:serverTimestamp(),members:{owner:{proof,joinedAt:serverTimestamp()}}}));
 await assertSucceeds(set(ref(guest,path+'/members/guest'),{proof,joinedAt:serverTimestamp()}));
 return {owner,guest,stranger};
}
const message=()=>({packet:'YWJjZGVmZ2hpamtsbW5vcA',createdAt:serverTimestamp()});
test('only authenticated ping; no public room enumeration',async()=>{
 const {owner,stranger}=await fixture();
 await assertSucceeds(get(ref(owner,'nogar/v1/ping')));
 await assertFails(get(ref(env.unauthenticatedContext().database(),'nogar/v1/ping')));
 await assertFails(get(ref(stranger,'nogar/v1/rooms')));
 await assertFails(get(ref(stranger,path+'/owner')));
 await assertFails(set(ref(env.unauthenticatedContext().database(),path+'/pulse'),serverTimestamp()));
});
test('admission proof required; cannot join as a different UID or replace ownership',async()=>{
 const {owner,stranger}=await fixture();
 await assertFails(set(ref(stranger,path+'/members/stranger'),{proof:'b'.repeat(64),joinedAt:serverTimestamp()}));
 await assertFails(set(ref(stranger,path+'/members/guest2'),{proof,joinedAt:serverTimestamp()}));
 await assertFails(set(ref(stranger,path+'/owner'),'stranger'));
 await assertFails(set(ref(owner,path+'/owner'),'stranger'));
});
test('private inbox and authenticated sender; peers route through organizer',async()=>{
 const {owner,guest,stranger}=await fixture();
 await assertSucceeds(set(ref(guest,path+'/inbox/owner/guest/a'),message()));
 await assertSucceeds(get(ref(owner,path+'/inbox/owner')));
 await assertFails(get(ref(guest,path+'/inbox/owner')));
 await assertFails(get(ref(stranger,path+'/inbox/owner')));
 await assertFails(set(ref(guest,path+'/inbox/guest/owner/forged'),message()));
 await set(ref(stranger,path+'/members/stranger'),{proof,joinedAt:serverTimestamp()});
 await assertFails(set(ref(guest,path+'/inbox/stranger/guest/a'),message()));
 await assertSucceeds(set(ref(owner,path+'/inbox/guest/owner/reply'),message()));
});
test('only opaque packets with bounded size; no plaintext GPS fields',async()=>{
 const {guest}=await fixture();const target=ref(guest,path+'/inbox/owner/guest/a');
 await assertFails(set(target,{packet:'{"lat":45}',createdAt:serverTimestamp()}));
 await assertFails(set(target,{...message(),lat:45,lng:12}));
 await assertFails(set(target,{...message(),packet:'a'.repeat(90001)}));
 await assertSucceeds(set(target,message()));
 await assertFails(set(target,{...message(),packet:'tampered'}));
 await assertSucceeds(set(target,message())); // retries may refresh only delivery timestamp.
});
test('recipient acknowledges and owner removes room; outsiders cannot delete',async()=>{
 const {owner,guest,stranger}=await fixture();
 await set(ref(guest,path+'/inbox/owner/guest/a'),message());
 await assertFails(remove(ref(stranger,path)));
 await assertFails(remove(ref(guest,path)));
 await assertSucceeds(update(ref(owner,path+'/inbox/owner'),{'guest/a':null}));
 await assertSucceeds(remove(ref(guest,path+'/inbox/guest')));
 await assertSucceeds(remove(ref(guest,path+'/members/guest')));
 await assertFails(get(ref(guest,path+'/owner')));
 await assertSucceeds(remove(ref(owner,path)));
});
test('expired rooms and missing host heartbeat reject message reads/writes and joins',async()=>{
 const {owner,guest,stranger}=await fixture();
 await env.withSecurityRulesDisabled(async admin=>{await set(ref(admin.database(),path+'/pulse'),Date.now()-121000)});
 await assertFails(get(ref(guest,path+'/inbox/guest')));
 await assertFails(set(ref(guest,path+'/inbox/owner/guest/a'),message()));
 await assertFails(set(ref(stranger,path+'/members/stranger'),{proof,joinedAt:serverTimestamp()}));
 await set(ref(owner,path+'/pulse'),serverTimestamp());
 await env.withSecurityRulesDisabled(async admin=>{await set(ref(admin.database(),path+'/createdAt'),Date.now()-10800001)});
 await assertFails(set(ref(guest,path+'/inbox/owner/guest/a'),message()));
 await assertFails(get(ref(guest,path+'/inbox/guest')));
 await assertSucceeds(remove(ref(owner,path))); // cleanup still possible after expiration.
});
