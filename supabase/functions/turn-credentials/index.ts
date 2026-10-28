import { handle } from './handler.mjs';
Deno.serve((request: Request) => handle(request, { env: (name: string) => Deno.env.get(name) }));
