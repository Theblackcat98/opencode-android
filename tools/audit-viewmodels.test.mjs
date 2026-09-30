#!/usr/bin/env node
// The audit's own tests: it has to find the class, its annotation, and an opt-out, across a multi-line
// constructor, or a green run means nothing.
import assert from 'node:assert/strict'
import { audit, viewModelsIn } from './audit-viewmodels.mjs'

const hilt = `
@HiltViewModel
class GoodViewModel @Inject constructor(
    private val a: A,
    private val b: B,
) : ViewModel() {
}
`
const bare = `
/** Docs. */
class BadViewModel(
    private val a: A,
) : ViewModel() {
}
`
const oneLine = `class TinyViewModel(private val a: A) : ViewModel() {}`
const manual = `
// audit-viewmodels: manual
class FactoryBuiltViewModel(
    private val a: A,
) : ViewModel() {
}
`
const notAViewModel = `class Plain(private val a: A) : Something() {}`

assert.deepEqual(viewModelsIn(hilt).map((v) => v.name), ['GoodViewModel'])
assert.deepEqual(viewModelsIn(bare).map((v) => v.name), ['BadViewModel'])
assert.deepEqual(viewModelsIn(oneLine).map((v) => v.name), ['TinyViewModel'])
assert.deepEqual(viewModelsIn(notAViewModel), [])

assert.deepEqual(audit([['a.kt', hilt]]), [])
assert.equal(audit([['b.kt', bare]]).length, 1)
assert.match(audit([['b.kt', bare]])[0], /BadViewModel is a ViewModel without @HiltViewModel/)
assert.equal(audit([['c.kt', oneLine]]).length, 1)
assert.deepEqual(audit([['d.kt', manual]]), [])
assert.equal(audit([['all.kt', hilt + bare + manual]]).length, 1)

console.log('audit-viewmodels.test: ok')
