CRITICAL RULES
1) Never ever put logic in the draw method. Put logic in the update method.
2) Always use layer depth when drawing game objects and have SpriteSortMode set to FrontToBack or BackToFront.
3) There should only be one spriteBatch.Begin and one spriteBatch.End in the entire game.
4) Implement a state machine in order to divide your game into functional states that are isolated from each other.
5) Stop redoing the same work. Reuse components.
6) If there's a bug, warning or error - fix it IMMEDIATELY.
7) GameRoot.cs is not a trash bin, new logic must be placed somewhere else.
8) Only allowed to use npm commands defined in the package.json.
- Run the server executable wrapper (requires server/.venv to exist)
npm run server
- Run server setup (create venv and install dependencies)
npm run server:build
- Regenerate galaxy sample files (same as the generators above)
npm run generate
- Run the wiki server (same as `python wiki/app.py`)
npm run wiki
- Run the wiki in watch mode (auto-rebuild CSS on SCSS changes)
npm run wiki:watch
- Build the client (alias for dotnet build)
npm run client:build
- Run the client (alias for dotnet run)
npm run client
9) not allowed to use dotnet commands directly EVER.
10) Do not create PowerShell or Python scripts without permission.
11) Before doing any task ask questions to clarify and always present a plan of action.
12) Don't create garbage files, and don't create any scripts without permission.
13) Do not hardcode secrets or sensitive information in code.
14) the HUD console is a gameplay feature, not a debug tool.
15) no error suppression, always fix the root cause.
16) Always write code with performance in mind.
17) Always write code with security in mind.
18) Always write code with maintainability in mind.
19) Always write code with readability in mind.
20) Always write code with extensibility in mind.
21) Do not use fallbacks, always fix the root cause or throw error. Fallbacks hide problems.
22) You do not have permission to run tests. All tests are run manually by user.