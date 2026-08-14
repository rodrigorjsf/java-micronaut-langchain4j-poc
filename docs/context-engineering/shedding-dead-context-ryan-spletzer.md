# Shedding Dead Context

**Source:** https://www.spletzer.com/2026/03/shedding-dead-context/
**Category:** Context Engineering

## Summary

Ryan Spletzer's personal account of discovering that 40% of his 200K Claude Code context window was consumed before any real work began — consumed by plugins, skills, and CLAUDE.md instructions he'd accumulated over time. The article introduces the concept of "dead context," uses the RAM analogy to explain context window mechanics, and provides practical memory management strategies for AI coding tools.

## Content

I have an oh-my-posh segment in my Claude Code status line that shows its context window usage as a little gauge — five bars that tick down as the session fills up.

The other day I opened a fresh session, typed one simple prompt, and watched two of the five bars vanish instantly.

**40% of my 200K context window — gone — before I'd done any real work.**

That was the moment I realized I had a problem.

---

## What's Eating Your Context Window

If you use Claude Code (or any AI coding tool with a plugin ecosystem), you've probably done what I did: installed every promising MCP server, enabled a bunch of skills, added a global `CLAUDE.md` packed with instructions, and bolted on project-level configs on top of that.

Each one felt like a small addition. Together, they were a tax I was paying on every single session.

Every plugin, every skill, every line in your `CLAUDE.md` occupies space in the context window. Even "lazy-loaded" tools that aren't fully active still carry a footprint — the model needs to keep a manifest of what's available, their descriptions, their trigger conditions, their tool schemas. None of that is free.

I call this _dead context_: instructions, tool definitions, and metadata sitting in the context window that aren't contributing to the task at hand.

---

## Your Context Window Is RAM

The analogy that made this click for me is simple.

The context window is like memory on a classical computer — RAM, specifically. It's the finite working space where the model holds everything it needs to reason about your problem.

And plugins are like running programs.

One VS Code window with all your extensions is fine on its own. Now open five or six of those windows, each with their own extension host and language servers, add five or six Claude Code terminals, and a browser with a hundred-plus tabs — and suddenly your machine is sluggish, spending more resources managing the tools than doing the work.

The same thing happens to an LLM. Every plugin and every instruction competes for the same finite resource the model needs to actually think about your code.

Dex Horthy of HumanLayer has talked about what he calls the "dumb zone" — that middle 40-60% of a large context window where model reasoning starts to degrade. Information placed there is more likely to be ignored or misinterpreted. The model drifts, forgets its own instructions, or hallucinates.

**If 40% of your context is consumed by dead weight before you start, you're beginning every session in the "dumb zone."**

---

## Bit Flips in the Context Window

The memory analogy goes further than just running out of space.

Think about rowhammer attacks on non-ECC memory: repeatedly accessing one row of physical memory causes electrical interference that flips bits in adjacent rows. The data doesn't just disappear — it _corrupts_. Values that were correct become wrong, and the system doesn't know it happened.

Something similar occurs when you overload a context window. The dead context doesn't just sit there inertly, taking up space. **It dilutes the signal.** The model's attention is spread across everything in the window, so the more irrelevant context you pack in, the less weight the relevant context carries. Instructions get misapplied, tool descriptions bleed into each other, and the model can confidently act on the wrong context without any indication that something went sideways.

It's not just forgetting. It's degradation — and like a bad DIMM, you might not realize it's happening until the output is already wrong.

---

## The Bigger-Window Trap

You might be thinking: "Just get a bigger context window."

Opus with 1M tokens is available now. GPT-5.4 pushed to 1.05M context. So with a larger context window, this problem is solved, yes?

Maybe.

**A bigger context window is analogous to more RAM on a machine with a memory leak.** It delays the symptoms without fixing the cause. And worse, it removes the pressure to be disciplined.

With 200K you're forced to be thoughtful about what you load. With 1M you can be sloppy, and it _appears_ to work — until it doesn't. When it fails with a million tokens of context, the failures are harder to diagnose because you can't easily pinpoint which of your million tokens caused the corruption.

Research like "Lost in the Middle" (Liu et al.) shows that models struggle to use information placed in the middle of long contexts, and a 2025 EMNLP finding demonstrated that context length _alone_ hurts performance even when the extra context is relevant. **More capacity does not mean better reasoning.**

Between the research and people's own hands-on experience, the _vibe_ is that the "dumb zone" is real, and longer context windows _may_ not necessarily yield better results. Discipline is a better bet than hope.

---

## Three Layers of the Same Problem

When I stepped back, I realized I was fighting the same battle on three fronts.

**The editor:** VS Code loaded with dozens of extensions — linters, formatters, language packs, themes, tools I tried once and forgot about. Each one adds overhead.

**The AI harness:** My global CLAUDE.md had grown into a sprawling instruction manual. I had plugins enabled globally that only mattered for specific projects. Skills had accumulated without pruning.

**Me.** I had five Claude Code instances running simultaneously, five VS Code windows open, and a browser with a mountain of tabs. I was doing to my own brain exactly what I was doing to the model: overloading my own context window with competing demands.

---

## The Plugin Paradox

Before you go uninstalling everything, there's an important nuance.

**Not all plugins are dead context.** Some farm out what would otherwise be a token-intensive task to deterministic tools, and further some actually reduce overall token consumption by fetching _precisely_ what the model needs without dumping everything into the window.

- A plugin that `cat`'s an entire file into context is expensive.
- A plugin that searches for the relevant function and returns just that? It _saves_ context.

The question isn't "how many plugins do I have?" It's "what's the ROI of each one?"

Some plugins add a small overhead to the context but prevent the model from doing expensive, wasteful exploration on its own. Those are keepers. The ones sitting there occupying space for a capability you use once a month? Dead context.

---

## Memory Management Strategies

If the context window is RAM, then optimizing it is memory management.

### Scope your editor per project
Launch VS Code with only the extensions listed in a project's `.vscode/extensions.json`. Everything else gets disabled for that session. Each project only loads what it actually needs.

### Audit your CLAUDE.md
Your global and project-level `CLAUDE.md` files are prime candidates for dead context. Instructions that made sense three months ago might be irrelevant now.

Ask Claude itself to audit your config: _"Review my `CLAUDE.md` and identify anything that could be removed, consolidated, or moved to a skill that only loads when needed."_

Skills are the equivalent of swapping to disk. The full instructions only load when invoked, instead of sitting in memory on every session.

### Scope your plugins per project
Not every project needs every MCP server. Set project-level `.claude/settings.json` files that enable only the plugins relevant to that codebase. A Jekyll blog doesn't need a database plugin. An API project doesn't need a Playwright plugin.

### Revisit regularly
This isn't a one-time cleanup. Context cruft accumulates the way clutter accumulates in a house. You install a new skill, try a new MCP server, add a line to your `CLAUDE.md`. Each one is small. Over time they add up.

---

## The Compaction Dread

If you've used Claude Code in a long session, you know the feeling.

You're watching the status bar tick down. Each prompt costs tokens. Each response costs more. You start doing mental math — can I fit one more big ask in, or will it push me over?

Eventually the session compacts — the system compresses prior messages to free up space — and you lose fidelity. The thread of what you were building together gets thinner.

Every token of dead context in your session is a token stolen from this budget. All those plugin manifests and stale instructions are crowding out the space you need for the actual back-and-forth of getting work done.

The irony is that the dead context was supposed to _help_.

---

## Use AI to Fix Your AI

The best tool for shedding dead context is the very AI you're trying to optimize.

Ask Claude to generate your `.vscode/extensions.json` for a project. Ask it to review your `CLAUDE.md` and suggest what to cut or optimize or push into a skill. Ask it to set up a scoped `.claude/settings.json` with only the plugins you need. Ask it to create skills for instructions that don't need to be loaded every session.

There's a nice recursion to it: the model, operating within its own constrained context window, helping you make that context window less constrained for the next session.

It won't tell you to install fewer things. You have to bring the philosophy. But once you know what you want to shed, it's remarkably good at doing the shedding.

---

## Less Is More

Being a power user means understanding the constraints of the system you're working with and operating within them deliberately. It means knowing that a 200K context window is a budget, and that every token of dead context is a token you can't spend on the work that matters.

**Shed the dead context. Your AI will think more clearly.**

And you need to manage your own context window as a human, too. Please everyone on this AI roller coaster, give yourself some grace and space, and go take a nap.
