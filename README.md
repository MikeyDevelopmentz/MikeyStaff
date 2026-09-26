# MikeyStaff

Staff plugin for Paper 1.21+, plus an optional Velocity plugin if you run a network. everything gets stored in MySQL.

what it does:

- staff mode, auto vanishes you and backs up your inventory first so you get it back even after a crash
- vanish with silent join/leave
- freeze, with an optional time and reason
- ban, mute, kick, unban, unmute. add `--silent` to a ban to skip the broadcast
- reports and staff notes
- invsee, ecsee and an inspect gui (punishments, freezes, reports, ip history, alts, login logs)
- staff chat
- tp and tphere
- PlaceholderAPI placeholders under `%staff_...%` if papi is installed
- works on folia too, same paper jar

## Commands

| usage | what it does |
|---|---|
| `/staffmode [reload]` | toggle staff mode, or reload the configs |
| `/vanish [player]` | toggle vanish for you or someone else |
| `/vanishlist` | list vanished players |
| `/freeze <player> [time] [reason]` | freeze a player |
| `/unfreeze <player>` | unfreeze a player |
| `/freezelist` | list frozen players |
| `/invsee <player>` | look at someones inventory |
| `/ecsee <player>` | look at someones ender chest |
| `/inspect <player>` | open the inspect gui |
| `/note add <player> <text>` / `/note remove <id>` | add or remove a note |
| `/notes <player> [page]` | see a players notes |
| `/staffchat [message]` | send to staff chat, or toggle it with no message |
| `/tp <player>` | tp to a player |
| `/tphere <player>` | tp a player to you |
| `/ban <player> [time] [reason]` | ban |
| `/unban <player>` | unban |
| `/mute <player> [time] [reason]` | mute |
| `/unmute <player>` | unmute |
| `/kick <player> [reason]` | kick |
| `/report <player> <reason>` | report a player |
| `/reports [gui\|list <player> [page]\|clear <player>]` | view and manage reports |

times look like `30m`, `1h`, `1d`, `2w` (`s` works too), or `perm`.

## Permissions

you can change all of these in `settings.yml`, these are the defaults:

- staff mode: `staff.staffmode.use`, `staff.staffmode.reload`
- vanish: `staff.vanish.self`, `staff.vanish.other`, `staff.vanish.list`
- freeze: `staff.freeze.use`, `staff.freeze.list`, `staff.freeze.logout-notify`
- inventory: `staff.inventory.invsee`, `staff.inventory.ecsee`
- inspect: `staff.inspect`
- notes: `staff.notes.add`, `staff.notes.remove`, `staff.notes.view`
- reports: `staff.reports.view`, `staff.reports.clear`. anyone can `/report` by default
- staff chat: `staff.staffchat`
- teleport: `staff.teleport.tp`, `staff.teleport.tphere`
- punishments: `staff.punishments.ban`, `staff.punishments.ban-notify`, `staff.punishments.unban`, `staff.punishments.mute`, `staff.punishments.unmute`, `staff.punishments.kick`

## Setup

1. build it (see below) or grab a release
2. put the paper jar in your servers `plugins/` folder, and the velocity jar in the proxys `plugins/` folder if you use one
3. start it once, set up the db in `settings.yml` (and `config.properties` on velocity), restart

config files:

- `settings.yml` db, network mode, features, perms
- `messages.yml` all the messages
- `items.yml` staff mode hotbar items
- `punishments.yml` time and reason presets
- `config.properties` (velocity) db, shared secret and `database.enforcement-interval-seconds` (how often the proxy rechecks bans/mutes)

`network.mode` in `settings.yml` needs a restart to change:

- `proxy` (default) uses the velocity plugin for cross server stuff and staff chat. set the same `network.shared-secret` on paper and velocity. if its blank on velocity every proxy login gets denied, so use a long random value and keep it private
- `paper` runs without velocity, doesnt register any plugin message channels and doesnt need a secret. local staff chat and bans still work

more than one proxy works too, no redis needed. point them all at the same db and shared secret. each proxy needs its own `network.proxy-id` in its `config.properties`, it gets generated on first start, so if you copy a proxy folder blank it on the copy.

staff mode wont replace your inventory unless it got backed up first, so if `features.staffmode.save-inventory` is false and staff items are on it just refuses. open sessions get restored on join or reload.

alt bans are off by default. turn them on with `features.alt-ban.enabled: true` and set `action` to `kick` or `ban`. ban needs a positive `ban-duration-seconds`, it never perm bans alts. keep `require-forwarding: true` behind velocity so it doesnt treat the proxy ip as everyones ip, only set it false if paper runs on its own. alt bans also turn on ip logging even if `features.inspect.log-ips` is false.

## Building

needs JDK 21. MikeyCore gets pulled in as a composite build, so clone it next to this repo:

```
parent/
├── MikeyStaff/
└── MikeyCore/
```

then:

```bash
./gradlew coreTest build
```

`coreTest` runs the MikeyCore tests too, `build` builds and tests both plugins. jars end up in `plugins/advanced-staff/paper/build/libs` and `plugins/advanced-staff/velocity/build/libs`, core gets shaded into both.

CI checks out MikeyCore next to it and builds both. every push to main updates the `latest build` release with the paper and velocity jars.
