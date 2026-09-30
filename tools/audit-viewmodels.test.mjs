#!/usr/bin/env node
// The audit's own tests: it has to find the class, its annotation, and an opt-out, across a multi-line
// constructor, or a green run means nothing. The second half holds the scope rules to the two shapes that
// shipped: a ViewModel defaulted on the function that builds the NavHost, and a second ComposerViewModel in
// a host that never opens a session.
import assert from 'node:assert/strict'
import { audit, auditScopes, blanked, functionsIn, viewModelsIn } from './audit-viewmodels.mjs'

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

// ---------------------------------------------------------------------------------- which instance

// The shape that shipped: the graph function defaulted a composer, so "Undo to here" and the file browser's
// attachments reached an instance in the activity's owner that no session was ever opened in.
const oldGraph = `
@Composable
fun OpenCodeApp(
    sharedPayload: String? = null,
    navController: NavHostController = rememberNavController(),
    composer: ComposerViewModel = hiltViewModel(),
    /**
     * Passed in rather than injected, because \`hiltViewModel()\` is the only injection Routes.kt does.
     */
    serverDataSets: ServerDataRegistry,
) {
    NavHost(navController = navController, startDestination = ServersRoute) {
        composable<SessionRoute> { entry ->
            SessionHost(onUndoConfirmed = { composer.stageUndo(it) })
        }
    }
}
`
const fixedGraph = `
@Composable
fun OpenCodeApp(
    navController: NavHostController = rememberNavController(),
    serverDataSets: ServerDataRegistry,
) {
    NavHost(navController = navController, startDestination = ServersRoute) {
        composable<SessionRoute> { entry ->
            val composer = hiltViewModel<ComposerViewModel>(entry)
            SessionHost(composer = composer)
        }
    }
}
`
const oldGraphProblems = auditScopes([['Routes.kt', oldGraph]])
assert.equal(oldGraphProblems.length, 2, oldGraphProblems.join('\n'))
assert.match(oldGraphProblems[0], /Routes\.kt:6 {2}OpenCodeApp\(composer: ComposerViewModel = hiltViewModel\(\)\)/)
assert.match(oldGraphProblems[0], /builds the NavHost/)
assert.match(oldGraphProblems[1], /second ComposerViewModel beside the one SessionHost\.kt opens/)
assert.deepEqual(auditScopes([['Routes.kt', fixedGraph]]), [])

// The graph rule is about any ViewModel, not just the composer.
const otherGraph = oldGraph.replace('composer: ComposerViewModel', 'other: TimelineViewModel')
assert.equal(auditScopes([['Routes.kt', otherGraph]]).length, 1)
// A NavHost function that takes a ViewModel with no default leaves the choice to its caller, and is fine.
const noDefault = oldGraph.replace('composer: ComposerViewModel = hiltViewModel()', 'composer: ComposerViewModel')
assert.deepEqual(auditScopes([['Routes.kt', noDefault]]), [])

// The shape that shipped in the review: a host that defaults its own composer beside the session's.
const reviewHost = `
@Composable
fun ReviewHost(
    sessionId: String?,
    modifier: Modifier = Modifier,
    review: ReviewViewModel = hiltViewModel(),
    composer: ComposerViewModel = hiltViewModel(),
) {
    LaunchedEffect(state.comments) { composer.setReviewComments(state.comments) }
}
`
const reviewProblems = auditScopes([['app/ReviewHost.kt', reviewHost]])
assert.equal(reviewProblems.length, 1, reviewProblems.join('\n'))
assert.match(reviewProblems[0], /app\/ReviewHost\.kt:7 {2}ReviewHost\(composer: ComposerViewModel = hiltViewModel\(\)\)/)
// Handed the session's instance (no default), it is fine.
const handedIn = reviewHost.replace('composer: ComposerViewModel = hiltViewModel()', 'composer: ComposerViewModel')
assert.deepEqual(auditScopes([['app/ReviewHost.kt', handedIn]]), [])
// The session's own host is where the composer is defaulted.
assert.deepEqual(auditScopes([['app/SessionHost.kt', reviewHost.replace('ReviewHost', 'SessionHost')]]), [])

// An own-instance claim above the parameter is accepted; one on another parameter is not a claim.
const claimed = reviewHost.replace(
  '    composer: ComposerViewModel = hiltViewModel(),',
  '    // audit-viewmodels: own-instance\n    // Answers requests by the session each names; never opens one.\n    composer: ComposerViewModel = hiltViewModel(),',
)
assert.deepEqual(auditScopes([['app/Inbox.kt', claimed]]), [])
const claimedElsewhere = reviewHost.replace(
  '    review: ReviewViewModel = hiltViewModel(),',
  '    // audit-viewmodels: own-instance\n    review: ReviewViewModel = hiltViewModel(),',
)
assert.equal(auditScopes([['app/ReviewHost.kt', claimedElsewhere]]).length, 1, 'a claim on another parameter is not a claim')

// The scanner reads code, not prose: comments and strings that mention the shape are not the shape.
const prose = `
/** \`composer: ComposerViewModel = hiltViewModel()\` is what went wrong, and NavHost( is where. */
fun Quiet(text: String = "composer: ComposerViewModel = hiltViewModel()") {
    // fun Nested(composer: ComposerViewModel = hiltViewModel()) {}
    val note = """fun Raw(composer: ComposerViewModel = hiltViewModel())"""
}
`
assert.deepEqual(auditScopes([['app/Quiet.kt', prose]]), [])
assert.deepEqual(functionsIn(prose).map((f) => f.name), ['Quiet'])
assert.equal(blanked(prose).length, prose.length)
assert.equal(blanked(prose).split('\n').length, prose.split('\n').length)

// Parameters are split on the commas that separate them, whatever their types hold.
const generic =
  'fun Host(a: Map<String, Int> = emptyMap(), onDone: (String, Int) -> Unit = { _, _ -> }, vm: ComposerViewModel = hiltViewModel()) {}'
assert.deepEqual(functionsIn(generic)[0].parameters.map((p) => p.name), ['a', 'onDone', 'vm'])
assert.equal(auditScopes([['app/Host.kt', generic]]).length, 1)

console.log('audit-viewmodels.test: ok')
