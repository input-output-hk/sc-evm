# Proof of Concept EVM Sidechain Client

## Notice

IOG will no longer be updating or maintaining this repository. 

After three months of experimentation with the proof-of-concept EVM sidechain testnet, the team gathered valuable learnings from this experience and this playground. New use cases and functionality were tested, feedback from the community was gathered, and we are now continuing to develop the interoperability solutions in line with the partnerchains framework announced at the Cardano Summit 2023.

All information included in this repository is considered publicly available and is free to be leveraged by developers to fork it to build and experiment with their own EVM sidechain solution. Should you have any questions, please reach out to the team on the [IOG Discord server](https://discord.com/invite/inputoutput).


The proof of concept (POC) EVM sidechain client is not production ready in its current form and use of it is entirely at
your own risk as this repository is not maintained. Please see [DISCLAIMER](./DISCLAIMER.md) for more information.

## Description

The Proof-of-Concept EVM Sidechain client is the sidechain client component described in the first version of the
sidechain toolkit specification. Written in Scala, the client runs as a sidechain to a Cardano network based on
Ouroboros-BFT.

### Building

The EVM Sidechain client uses dependencies that are hosted on GitHub Packages.
In order to build it, you need to obtain a GitHub personal access token and
pass it to SBT either via the `github.token` Java property or `GITHUB_TOKEN`
environment variable.

### Documentation

Some documentation is available in the [docs](./docs) folder.

The technical specification is [here](./TECHNICAL_SPECIFICATION_v1.1.pdf).

## Next steps

If you decide to investigate further, or add your own development, start with
the [development instructions](./DEVELOPMENT.md) and go from there
