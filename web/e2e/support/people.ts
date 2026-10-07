/** The people every run seeds, each with a handle unique to the run. */

const run = process.env.E2E_RUN ?? 'local';

export const PASSWORD = 'Correct-Horse-9x';

export interface Person {
  handle: string;
  fullName: string;
  email: string;
}

const person = (key: string, fullName: string): Person => {
  const handle = `${key}_${run}`;
  return { handle, fullName, email: `${handle}@e2e.example.com` };
};

export const people = {
  /** Has a long history: enough lines for several pages, sent and received. */
  asha: person('asha', 'Asha Menon'),
  ravi: person('ravi', 'Ravi Kumar'),
  meera: person('meera', 'Meera Iyer'),
  kabir: person('kabir', 'Kabir Shah'),
  /** A name with no spaces at all, which nothing on any screen may let escape its page. */
  wide: person('wide', 'W'.repeat(60)),
  /** Has never had any money, so their payment was refused. */
  broke: person('broke', 'Bina Rao'),
};

/** Promoted to auditor by the stack's ADMIN_EMAILS, so it is the same address every run. */
export const auditor: Person = {
  handle: 'e2e_auditor',
  fullName: 'Ira Auditor',
  email: 'e2e-auditor@example.com',
};

/** The note on Asha's payment to the wide name: the longest a note may be, with no spaces. */
export const WIDE_NOTE = 'W'.repeat(30);
