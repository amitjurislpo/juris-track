import { notFound } from "next/navigation";
import { AppError } from "@/lib/errors";

/** Converts a NOT_FOUND AppError (including out-of-scope access) into a 404 page. */
export async function orNotFound<T>(promise: Promise<T>): Promise<T> {
  try {
    return await promise;
  } catch (e) {
    if (e instanceof AppError && e.code === "NOT_FOUND") notFound();
    throw e;
  }
}
