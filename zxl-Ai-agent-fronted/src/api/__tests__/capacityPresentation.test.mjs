import test from 'node:test';import assert from 'node:assert/strict';
import {capacityMessage} from '../../utils/capacityPresentation.ts';
test('tenant quota explains shared tenant occupancy in both languages',()=>{
 assert.match(capacityMessage('AGENT_TENANT_CAPACITY_EXCEEDED',true),/工作空间.*运行.*上限/);
 assert.match(capacityMessage('AGENT_TENANT_CAPACITY_EXCEEDED',false),/workspace.*run.*limit/);
 assert.notEqual(capacityMessage('AGENT_TENANT_CAPACITY_EXCEEDED',false),capacityMessage('AGENT_CAPACITY_EXCEEDED',false));
});
test('queue expiry remains distinct from capacity rejection and unknown errors do not get reclassified',()=>{
 assert.match(capacityMessage('CAPACITY_QUEUE_TIMEOUT',true),/排队/);assert.match(capacityMessage('CAPACITY_QUEUE_TIMEOUT',false),/queue/);
 assert.equal(capacityMessage('PRIVATE_UNKNOWN',true),undefined);
});
