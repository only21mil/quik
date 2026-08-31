# Buzz-first source promotion

Buzz is authoritative for `only21mil/quik`. GitHub is its public CI mirror.
The bootstrap commit on `main` is
`4706cb38f9d7d38c5b2dd971286fcded51e180a3`. Its sole parent is the untouched
upstream `master` commit
`555b8822c654b8ee85bb9d3f961eb30f232b1079`.

The reaction work was developed across temporary branches. Do not merge that
history onto `main`. Once the final corrected tree has passed its checks, use
[`prepare-buzz-first-promotion.sh`](../scripts/prepare-buzz-first-promotion.sh)
to create one local candidate commit with the bootstrap commit as its sole
parent and the settled commit's exact tree.

The controller creates only the named `refs/heads/sats/` candidate ref. It
requires local `main` and `master` to match the two commits above, then proves
that neither ref moved. It has no fetch, push, API, credential, signing, build,
or remote-mutation path.

Prepare a plain-text commit message outside the repository, then run:

```bash
export QUIK_PROMOTION_AUTHOR_NAME='approved author name'
export QUIK_PROMOTION_AUTHOR_EMAIL='approved author email'
export QUIK_PROMOTION_TIMESTAMP='approved ISO-8601 timestamp'

scripts/prepare-buzz-first-promotion.sh create \
  --settled FULL_SETTLED_COMMIT \
  --candidate-ref refs/heads/sats/buzz-promotion-FULL_SETTLED_COMMIT \
  --message-file /absolute/path/to/message.txt
```

The candidate ref must not already exist. The command prints the candidate,
parent, tree, and unchanged `main` and `master` values. It does not publish the
candidate. A later reviewed and explicitly approved Buzz operation must push
the candidate to a Buzz topic ref, run CI through the GitHub mirror, and move
Buzz `main` only after every required gate passes.

Run the local controller test with:

```bash
scripts/tests/test-buzz-first-promotion.sh
```
