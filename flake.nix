# A placeholder, not the real configuration: the scripts find "the repository" as the
# nearest directory with a flake.nix above them (scala-lib/Script.scala's Repo.root), and
# read hosts/ and tests/checks/ there. With these, every variant's --help, `test.scala -l`
# and argument checks work here; anything that calls nix or ssh needs the real repository.
{ outputs = _: { }; }
