CRITICAL RULES
1) Never ever put logic in the draw method. Put logic in the update method.
2) Always use layer depth when drawing game objects and have SpriteSortMode set to FrontToBack or BackToFront.
3) There should only be one spriteBatch.Begin and one spriteBatch.End in the entire game.
4) Implement a state machine in order to divide your game into functional states that are isolated from each other.
5) Stop redoing the same work. Reuse components.
6) If there's a bug, warning or error - fix it IMMEDIATELY.
7) Before doing any task ask questions to clarify and always present a plan of action.
8) Don't create garbage files, and don't create any scripts without permission.
9) Do not hardcode secrets or sensitive information in code.
10) no error suppression, always fix the root cause.
11) Always write code with performance in mind.
12) Always write code with security in mind.
13) Always write code with maintainability in mind.
14) Always write code with readability in mind.
15) Always write code with extensibility in mind.
16) Do not use fallbacks, always fix the root cause or throw error. Fallbacks hide problems.
17) You do not have permission to run tests. All tests are run manually by user.
18) Do not run gradle commands. If needed request the user run them.