# RPC endpoints documentation

We are currently using the playground-openrpc.org website to display RPC endpoints documentation.

Here are some useful tips to use for formatting the description section of an endpoint.

To create new lines in a description, use `\r \n` (notice the space between the two characters) as `\n` and `\r\n` are not working.

The Markdown syntax is also available. Here are some examples:

- you can create list with `\r \n- text` (unordered) or `\r \n1. text` (ordered). You can also add a checkbox using `\r \n- [ ] text` (unchecked) or `\r \n- [x] text` (checked) (available for both unordered and ordered list). To create a second level, just add two spaces before the `-` like that: `\r \n - text`
- you can modify the appearance of your text:
  - title: `# text`, `## text`, `### text`, `#### text` or `##### text` (if not used at the beginning of the description, a new line is needed before)
  - italic: `_text_` or `*text*`
  - bold: `__text__` or `**text**`
  - strikethrough: `~~text~~`
  - code: `text`
